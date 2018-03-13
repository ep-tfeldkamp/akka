/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import org.scalatest.Matchers
import org.scalatest.WordSpec

object DslConsistencySpec {
  class ScalaSubSource[Out, Mat] extends impl.SubFlowImpl[Out, Out, Mat, scaladsl.Source[Out, Mat]#Repr, scaladsl.RunnableGraph[Mat]](null, null, null)
  class ScalaSubFlow[In, Out, Mat] extends impl.SubFlowImpl[Out, Out, Mat, scaladsl.Flow[In, Out, Mat]#Repr, scaladsl.Sink[In, Mat]](null, null, null)
}

class DslConsistencySpec extends WordSpec with Matchers {

  val sFlowClass: Class[_] = classOf[akka.stream.scaladsl.Flow[_, _, _]]

  val sSubFlowClass: Class[_] = classOf[DslConsistencySpec.ScalaSubFlow[_, _, _]]

  val sSourceClass: Class[_] = classOf[akka.stream.scaladsl.Source[_, _]]

  val sSubSourceClass: Class[_] = classOf[DslConsistencySpec.ScalaSubSource[_, _]]

  val sSinkClass: Class[_] = classOf[akka.stream.scaladsl.Sink[_, _]]

  val sRunnableGraphClass: Class[_] = classOf[akka.stream.scaladsl.RunnableGraph[_]]

  val ignore =
    Set("equals", "hashCode", "notify", "notifyAll", "wait", "toString", "getClass") ++
      Set("productArity", "canEqual", "productPrefix", "copy", "productIterator", "productElement") ++
      Set("create", "apply", "ops", "appendJava", "andThen", "andThenMat", "isIdentity", "withAttributes", "transformMaterializing") ++
      Set("asScala", "asJava", "deprecatedAndThen", "deprecatedAndThenMat")

  val graphHelpers = Set("zipGraph", "zipWithGraph", "mergeGraph", "mergeSortedGraph", "interleaveGraph", "concatGraph", "prependGraph", "alsoToGraph", "orElseGraph")
  val allowMissing: Map[Class[_], Set[String]] = Map(
    sFlowClass → Set("of"),
    sSourceClass → Set("adapt", "from"),
    sSinkClass → Set("adapt"),
    sRunnableGraphClass → Set("builder"))

  def materializing(m: Method): Boolean = m.getParameterTypes.contains(classOf[ActorMaterializer])

  def assertHasMethod(c: Class[_], name: String): Unit = {
    // include class name to get better error message
    if (!allowMissing.getOrElse(c, Set.empty).contains(name))
      c.getMethods.collect { case m if !ignore(m.getName) ⇒ c.getName + "." + m.getName } should contain(c.getName + "." + name)
  }

}
