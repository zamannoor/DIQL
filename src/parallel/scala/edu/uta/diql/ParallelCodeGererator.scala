/*
 * Copyright © 2017 University of Texas at Arlington
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.uta.diql.core

import scala.collection.parallel.ParIterable
import scala.reflect.ClassTag
import scala.reflect.macros.whitebox.Context


abstract class ParallelCodeGenerator extends DistributedCodeGenerator {
  import c.universe.{Expr=>_,_}
  import AST._

  override def typeof ( c: Context ) = c.typeOf[ParIterable[_]]

  override def mkType ( c: Context ) ( tp: c.Tree ): c.Tree = {
    import c.universe._
    tq"ParIterable[$tp]"
  }

  /** Is tp a data stream? */
  override def isStream ( c: Context ) ( tp: c.Type ): Boolean = false

  override val datasetClassPath = "scala.collection.parallel.ParIterable"

  def initialize[K,V] ( ignore: String, v: Traversable[(K,V)] ): ParIterable[(K,V)]
    = v.toIterable.par

  def merge[K,V] ( v: Traversable[(K,V)], op: (V,V)=>V, s: ParIterable[(K,V)] ): Array[(K,V)]
    = merge(v,op,s.toList)

  def merge[K,V] ( v: Traversable[(K,V)], op: (V,V)=>V, s: Traversable[(K,V)] ): Array[(K,V)]
    = inMemory.coGroup(v,s).map{ case (k,(xs,ys)) => (k,(xs++ys).reduce(op)) }.toArray

  def merge[K,V] ( v: ParIterable[(K,V)], op: (V,V)=>V, s: Traversable[(K,V)] ): ParIterable[(K,V)]
    = coGroup(v,s).map{ case (k,(xs,ys)) => (k,(xs++ys).reduce(op)) }

  def merge[K,V] ( v: ParIterable[(K,V)], op: (V,V)=>V, s: ParIterable[(K,V)] ): ParIterable[(K,V)]
    = coGroup(v,s).map{ case (k,(xs,ys)) => (k,(xs++ys).reduce(op)) }

  /** Implementation of the algebraic operations in Scala's Parallel library
   */
  def flatMap[A,B] ( f: (A) => TraversableOnce[B], S: ParIterable[A] ): ParIterable[B]
    = S.flatMap(f)

  def flatMap2[A,B] ( f: (A) => ParIterable[B], S: ParIterable[A] ): ParIterable[B]
    = S.flatMap(f(_).toSeq)

  // bogus; used for type-checking only
  def flatMap[A,B] ( f: (A) => ParIterable[B], S: Traversable[A] ): ParIterable[B]
    = f(S.head)

  def groupBy[K,A] ( S: ParIterable[(K,A)] ): ParIterable[(K,Iterable[A])]
    = S.groupBy(_._1).map{ case (k,s) => (k,s.map(_._2).toList) }.toIterable

  def orderBy[K,A] ( S: ParIterable[(K,A)] ) ( implicit ord: Ordering[K] ): ParIterable[A]
    = S.toList.sortBy(_._1).map(_._2).par  // no parallel sorting in Scala

  def reduce[A] ( acc: (A,A) => A, S: ParIterable[A] ): A
    = S.reduce(acc)

  private def partitionMap[A1,A2] ( s: List[Either[A1,A2]] ): (List[A1], List[A2]) = {
      val l = scala.collection.mutable.ListBuffer.empty[A1]
      val r = scala.collection.mutable.ListBuffer.empty[A2]
      s.foreach {
          case Left(x1) => l += x1
          case Right(x2) => r += x2
      }
      (l.toList, r.toList)
    }

  def coGroup[K,A,B] ( X: ParIterable[(K,A)], Y: ParIterable[(K,B)] ): ParIterable[(K,(Iterable[A],Iterable[B]))]
    = ( X.map{ case (k,v) => (k,Left(v).asInstanceOf[Either[A,B]]) }
        ++ Y.map{ case (k,v) => (k,Right(v).asInstanceOf[Either[A,B]]) } )
      .groupBy(_._1)
      .map{ case (k,s) => ( k, partitionMap(s.map(_._2).toList) ) }.toIterable

  def join[K,A,B] ( X: ParIterable[(K,A)], Y: ParIterable[(K,B)] ): ParIterable[(K,(A,B))] = {
      val h = Y.groupBy(_._1)
      X.flatMap{ case (k,x) => h.get(k) match { case Some(s) => s.map(y => (k,(x,y._2))); case _ => Nil } }
  }

  def join[K,A,B] ( X: ParIterable[(K,A)], Y: Traversable[(K,B)] ): ParIterable[(K,(A,B))] = {
      val h = Y.groupBy(_._1)
      X.flatMap{ case (k,x) => h.get(k) match { case Some(s) => s.map(y => (k,(x,y._2))); case _ => Nil } }
  }

  def join[K,A,B] ( X: Traversable[(K,A)], Y: ParIterable[(K,B)] ): ParIterable[(K,(A,B))] = {
      val h = X.groupBy(_._1)
      Y.flatMap{ case (k,y) => h.get(k) match { case Some(s) => s.map(x => (k,(x._2,y))); case _ => Nil } }
  }

  def coGroup[K,A,B] ( X: Traversable[(K,A)], Y: ParIterable[(K,B)] ): ParIterable[(K,(Iterable[A],Iterable[B]))]
    = coGroup(X.toIterable.par,Y)

  def coGroup[K,A,B] ( X: ParIterable[(K,A)], Y: Traversable[(K,B)] ): ParIterable[(K,(Iterable[A],Iterable[B]))]
    = coGroup(X,Y.toIterable.par)

  def cross[A,B] ( X: ParIterable[A], Y: ParIterable[B] ): ParIterable[(A,B)]
    = { val ys = Y.toSeq
        X.flatMap( x => ys.map(y => (x,y)) )
      }

  def cross[A,B] ( X: Traversable[A], Y: ParIterable[B] ): ParIterable[(A,B)]
    = Y.flatMap( y => X.map(x => (x,y)) )

  def cross[A,B] ( X: ParIterable[A], Y: Traversable[B] ): ParIterable[(A,B)]
    = X.flatMap( x => Y.map(y => (x,y)) )

  def merge[A] ( X: ParIterable[A], Y: ParIterable[A] ): ParIterable[A]
    = X++Y

  def collect[A: ClassTag] ( X: ParIterable[A] ): Array[A]
    = X.toArray

  def cache[A] ( X: ParIterable[A] ): ParIterable[A]
    = X

  def head[A] ( X: ParIterable[A] ): A = X.head

  override def codeGen ( e: Expr, env: Environment ): c.Tree
    = if (!isDistributed(e))
        super.codeGen(e,env,codeGen)
      else e match {
        case flatMap(Lambda(TuplePat(List(k,TuplePat(List(xs,ys)))),
                            flatMap(Lambda(px,flatMap(Lambda(py,Elem(b)),ys_)),xs_)),
                     coGroup(x,y))
          if xs_ == toExpr(xs) && ys_ == toExpr(ys)
             && occurrences(patvars(xs)++patvars(ys),b) == 0
          => val (_,tq"($t1,$xtp)",xc) = typedCode(x,env,codeGen)
             val (_,tq"($t2,$ytp)",yc) = typedCode(y,env,codeGen)
             val kc = code(k)
             val pxc = code(px)
             val pyc = code(py)
             val bc = codeGen(b,add(px,xtp,add(py,ytp,env)))
             val join = if (smallDataset(x))
                           q"core.distributed.join($xc.toList,$yc)"
                        else if (smallDataset(y))
                           q"core.distributed.join($xc,$yc.toList)"
                        else q"core.distributed.join($xc,$yc)"
             q"$join.map{ case ($kc,($pxc,$pyc)) => $bc }"
        case flatMap(Lambda(TuplePat(List(k,TuplePat(List(xs,ys)))),
                            flatMap(Lambda(py,flatMap(Lambda(px,Elem(b)),xs_)),ys_)),
                     coGroup(x,y))
          if xs_ == toExpr(xs) && ys_ == toExpr(ys)
             && occurrences(patvars(xs)++patvars(ys),b) == 0
          => val (_,tq"($t1,$xtp)",xc) = typedCode(x,env,codeGen)
             val (_,tq"($t2,$ytp)",yc) = typedCode(y,env,codeGen)
             val kc = code(k)
             val pxc = code(px)
             val pyc = code(py)
             val bc = codeGen(b,add(px,xtp,add(py,ytp,env)))
             val join = if (smallDataset(x))
                           q"core.distributed.join($xc.toList,$yc)"
                        else if (smallDataset(y))
                           q"core.distributed.join($xc,$yc.toList)"
                        else q"core.distributed.join($xc,$yc)"
             q"$join.map{ case ($kc,($pxc,$pyc)) => $bc }"
        case flatMap(Lambda(p,Elem(b)),x)
          if irrefutable(p)
          => val pc = code(p)
             val (_,tp,xc) = typedCode(x,env,codeGen)
             val bc = codeGen(b,add(p,tp,env))
             if (toExpr(p) == b)
                xc
             else q"$xc.map{ case $pc => $bc }"
        case flatMap(Lambda(p,b),x)
          => val pc = code(p)
             val (_,tp,xc) = typedCode(x,env,codeGen)
             val bc = codeGen(b,add(p,tp,env))
             if (irrefutable(p))
                q"$xc.flatMap{ case $pc => $bc }"
             else q"$xc.flatMap{ case $pc => $bc; case _ => Nil }"
        case groupBy(x)
          => val xc = codeGen(x,env)
             q"core.distributed.groupBy($xc)"
        case orderBy(x)
          => val xc = codeGen(x,env)
             q"core.distributed.orderBy($xc)"
        case coGroup(x,y)
          => val xc = codeGen(x,env)
             val yc = codeGen(y,env)
             q"core.distributed.coGroup($xc,$yc)"
        case cross(x,y)
          => val xc = codeGen(x,env)
             val yc = codeGen(y,env)
             q"core.distributed.cross($xc,$yc)"
        case reduce(BaseMonoid("+"),
                    flatMap(Lambda(p,Elem(LongConst(1))),x))
          => val xc = codeGen(x,env)
             q"$xc.size"
        case reduce(m,x)
          => val (_,tp,xc) = typedCode(x,env,codeGen)
             val fm = accumulator(m,tp,e)
             monoid(c,m) match {
               case Some(mc)
                 => if (isDistributed(x))
                      q"$xc.fold[$tp]($mc)($fm)"
                    else q"$xc.foldLeft[$tp]($mc)($fm)"
               case _ => q"$xc.reduce($fm)"
             }
        case _ => super.codeGen(e,env,codeGen)
    }

  /** Convert method calls to algebraic terms so that they can be optimized */
  override def algebraGen ( e: Expr ): Expr = e
}
