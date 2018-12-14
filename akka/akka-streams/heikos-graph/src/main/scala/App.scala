import scala.concurrent.Future

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.stream._
import akka.stream.scaladsl._

/**
  * A quick port of Heiko's HTTP log tracing graph without akka-http.
  * All credit goes to him.  See README for a link to his video.
  *
  * Here's the graph:
  *
  *                   +---------------+
  *         +-------->| businessLogic |---------+
  *         |         +---------------+         |
  *         |                                   |
  *         |                                   v
  *    +----------+        +--------+       +----------+
  * -->| bcastStr |------->| enrich |------>| bcastInt |-->
  *    +----------+        +--------+       +----------+
  *                            |                |
  *                            v                |
  *    +---------------+   +-------+            |
  *    | processingLog |<--|  zip  |<-----------+
  *    +---------------+   +-------+
  */
object App {

  // The graph implementation of the ascii art.
  def withProcessingLog
    (enrich: String => String)
    (businessLogic: BusinessLogicFlow  // heiko's 'handler'
  ): BusinessLogicFlow = {
    Flow.fromGraph(GraphDSL.create(processingLog) { implicit builder => processingLog =>
      import GraphDSL.Implicits._

      val bcastStr = builder.add(Broadcast[String](2))
      val bcastInt = builder.add(Broadcast[Int](2))
      val enrichStr = builder.add(Flow[String].map(enrich))
      val zip = builder.add(Zip[String, Int])

      bcastStr ~> businessLogic ~> bcastInt
      bcastStr ~> enrichStr ~> zip.in0
      bcastInt.out(1) ~> zip.in1
      zip.out ~> processingLog

      FlowShape(bcastStr.in, bcastInt.out(0))
    })
  }


  def main(args: Array[String]): Unit = {

    implicit val system = ActorSystem("heikos-graph")
    implicit val mat = ActorMaterializer()
    implicit val ec = system.dispatcher

    val strings = Source(List("ab", "cdef"))

    def process(businessLogic: BusinessLogicFlow) =
      strings.via(businessLogic).runForeach(println)

    val done1 = process(someFlow)

    val done2 = process(withProcessingLog(s => s"In: $s")(someFlow))

    done1.zip(done2).onComplete(_ => system.terminate())
  }

  val someFlow: BusinessLogicFlow = Flow[String].map { _.length }

  type BusinessLogicFlow = Flow[String, Int, Any]
  type Log = Sink[(String, Int), Future[Done]]

  val processingLog: Log = {
    Sink.foreach {
      case (in, out) => println(s"$in = $out")
    }
  }
}
