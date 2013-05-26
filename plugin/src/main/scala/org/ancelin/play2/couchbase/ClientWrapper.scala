package org.ancelin.play2.couchbase

import play.api.libs.json._
import com.couchbase.client.CouchbaseClient
import scala.concurrent.{Promise, Future, ExecutionContext}
import net.spy.memcached.ops.OperationStatus
import com.couchbase.client.protocol.views.{SpatialView, DesignDocument, Query, View}
import collection.JavaConversions._
import net.spy.memcached.{ReplicateTo, PersistTo}
import akka.actor.ActorSystem
import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.TimeUnit
import net.spy.memcached.internal.OperationFuture
import com.couchbase.client.internal.HttpFuture
import play.api.Play.current
import play.api.Play

// Yeah I know JavaFuture.get is really ugly, but what can I do ???
// http://stackoverflow.com/questions/11529145/how-do-i-wrap-a-java-util-concurrent-future-in-an-akka-future
trait ClientWrapper {

  def find[T](docName:String, viewName: String, query: Query)(implicit client: CouchbaseClient, r: Reads[T], ec: ExecutionContext): Future[List[T]] = {
    view(docName, viewName)(client, ec).flatMap { view =>
      find[T](view, query)(client, r, ec)
    }
  }

  def find[T](view: View, query: Query)(implicit client: CouchbaseClient, r: Reads[T], ec: ExecutionContext): Future[List[T]] = {
    wrapJavaFutureInPureFuture( client.asyncQuery(view, query), ec ).map { results =>
      results.iterator().map { result =>
        r.reads(Json.parse(result.getDocument.asInstanceOf[String])) match {
          case e:JsError => None
          case s:JsSuccess[T] => s.asOpt
        }
      }.toList.filter(_.isDefined).map(_.get)
    }
  }

  def view(docName: String, viewName: String)(implicit client: CouchbaseClient, ec: ExecutionContext): Future[View] = {
    wrapJavaFutureInPureFuture( client.asyncGetView(docName, viewName), ec )
  }

  def spatialView(docName: String, viewName: String)(implicit client: CouchbaseClient, ec: ExecutionContext): Future[SpatialView] = {
    wrapJavaFutureInPureFuture( client.asyncGetSpatialView(docName, viewName), ec )
  }

  def designDocument(docName: String)(implicit client: CouchbaseClient, ec: ExecutionContext): Future[DesignDocument[_]] = {
    wrapJavaFutureInPureFuture( client.asyncGetDesignDocument(docName), ec )
  }

  def createDesignDoc(name: String, value: JsObject)(implicit client: CouchbaseClient, ec: ExecutionContext): Future[OperationStatus] = {
    wrapJavaFutureInFuture( client.asyncCreateDesignDoc(name, Json.stringify(value)), ec )
  }

  def createDesignDoc(name: String, value: String)(implicit client: CouchbaseClient, ec: ExecutionContext): Future[OperationStatus] = {
    wrapJavaFutureInFuture( client.asyncCreateDesignDoc(name, value), ec )
  }

  def createDesignDoc(value: DesignDocument[_])(implicit client: CouchbaseClient, ec: ExecutionContext): Future[OperationStatus] = {
    wrapJavaFutureInFuture( client.asyncCreateDesignDoc(value), ec )
  }

  def deleteDesignDoc(name: String)(implicit client: CouchbaseClient, ec: ExecutionContext): Future[OperationStatus] = {
    wrapJavaFutureInFuture( client.asyncDeleteDesignDoc(name), ec )
  }

  def keyStats(key: String)(implicit client: CouchbaseClient, ec: ExecutionContext): Future[Map[String, String]] = {
    wrapJavaFutureInPureFuture( client.getKeyStats(key), ec ).map(_.toMap)
  }

  def get[T](key: String)(implicit client: CouchbaseClient, r: Reads[T], ec: ExecutionContext): Future[Option[T]] = {
    wrapJavaFutureInPureFuture( client.asyncGet(key), ec ).map { f =>
       f match {
         case value: String => r.reads(Json.parse(value)).asOpt
         case _ => None
       }
    }
  }

  def set[T <: {def id:String}](value: T, exp: Int)(implicit client: CouchbaseClient, w: Writes[T], ec: ExecutionContext): Future[OperationStatus] = {
    set[T](value.id, exp, value)(client, w, ec)
  }

  def set[T <: {def id:String}](value: T)(implicit client: CouchbaseClient, w: Writes[T], ec: ExecutionContext): Future[OperationStatus] = {
    set[T](value.id, value)(client, w, ec)
  }

  def set[T](value: T, key: T => String, exp: Int)(implicit client: CouchbaseClient, w: Writes[T], ec: ExecutionContext): Future[OperationStatus] = {
    set[T](key(value), exp, value)(client, w, ec)
  }

  def set[T](value: T, key: T => String)(implicit client: CouchbaseClient, w: Writes[T], ec: ExecutionContext): Future[OperationStatus] = {
    set[T](key(value), value)(client, w, ec)
  }

  def set[T](value: T, exp: Int)(key: T => String)(implicit client: CouchbaseClient, w: Writes[T], ec: ExecutionContext): Future[OperationStatus] = {
    set[T](key(value), exp, value)(client, w, ec)
  }

  def set[T](value: T)(key: T => String)(implicit client: CouchbaseClient, w: Writes[T], ec: ExecutionContext): Future[OperationStatus] = {
    set[T](key(value), value)(client, w, ec)
  }

  // TODO : replicates & persists

  def set[T](key: String, exp: Int, value: T)(implicit client: CouchbaseClient, w: Writes[T], ec: ExecutionContext): Future[OperationStatus] = {
    wrapJavaFutureInFuture( client.set(key, exp, Json.stringify(w.writes(value))), ec )
  }

  def set[T](key: String, exp: Int, value: T, replicateTo: ReplicateTo)(implicit client: CouchbaseClient, w: Writes[T], ec: ExecutionContext): Future[OperationStatus] = {
    wrapJavaFutureInFuture( client.set(key, exp, value, replicateTo), ec )
  }
  
  def set[T](key: String, value: T, replicateTo: ReplicateTo)(implicit client: CouchbaseClient, w: Writes[T], ec: ExecutionContext): Future[OperationStatus] = {
    set(key, Polling.persist, value, replicateTo)(client, w, ec)
  }
  
  def set[T](key: String, exp: Int, value: T, persistTo: PersistTo)(implicit client: CouchbaseClient, w: Writes[T], ec: ExecutionContext): Future[OperationStatus] = {
    wrapJavaFutureInFuture( client.set(key, exp, value, persistTo), ec )
  }
  
  def set[T](key: String, value: T, persistTo: PersistTo)(implicit client: CouchbaseClient, w: Writes[T], ec: ExecutionContext): Future[OperationStatus] = {
    set(key, Polling.persist, value, persistTo)(client, w, ec)
  }
  
  def set[T](key: String, exp: Int, value: T, persistTo: PersistTo, replicateTo: ReplicateTo)(implicit client: CouchbaseClient, w: Writes[T], ec: ExecutionContext): Future[OperationStatus] = {
    wrapJavaFutureInFuture( client.set(key, exp, value, persistTo, replicateTo), ec )
  }
  
  def set[T](key: String, value: T, persistTo: PersistTo, replicateTo: ReplicateTo)(implicit client: CouchbaseClient, w: Writes[T], ec: ExecutionContext): Future[OperationStatus] = {
    set(key, Polling.persist, value, persistTo, replicateTo)(client, w, ec)
  }

  def add[T](key: String, exp: Int, value: T)(implicit client: CouchbaseClient, w: Writes[T], ec: ExecutionContext): Future[OperationStatus] = {
    wrapJavaFutureInFuture( client.add(key, exp, Json.stringify(w.writes(value))), ec )
  }

  def add[T](key: String, exp: Int, value: T, replicateTo: ReplicateTo)(implicit client: CouchbaseClient, w: Writes[T], ec: ExecutionContext): Future[OperationStatus] = {
    wrapJavaFutureInFuture( client.add(key, exp, value, replicateTo), ec )
  }
  
  def add[T](key: String, value: T, replicateTo: ReplicateTo)(implicit client: CouchbaseClient, w: Writes[T], ec: ExecutionContext): Future[OperationStatus] = {
    add(key, Polling.persist, value, replicateTo)(client, w, ec)
  }
  
  def add[T](key: String, exp: Int, value: T, persistTo: PersistTo)(implicit client: CouchbaseClient, w: Writes[T], ec: ExecutionContext): Future[OperationStatus] = {
    wrapJavaFutureInFuture( client.add(key, exp, value, persistTo), ec )
  }
  
  def add[T](key: String, value: T, persistTo: PersistTo)(implicit client: CouchbaseClient, w: Writes[T], ec: ExecutionContext): Future[OperationStatus] = {
    add(key, Polling.persist, value, persistTo)(client, w, ec)
  }
  
  def add[T](key: String, exp: Int, value: T, persistTo: PersistTo, replicateTo: ReplicateTo)(implicit client: CouchbaseClient, w: Writes[T], ec: ExecutionContext): Future[OperationStatus] = {
    wrapJavaFutureInFuture( client.add(key, exp, value, persistTo, replicateTo), ec )
  }
  
  def add[T](key: String, value: T, persistTo: PersistTo, replicateTo: ReplicateTo)(implicit client: CouchbaseClient, w: Writes[T], ec: ExecutionContext): Future[OperationStatus] = {
    add(key, Polling.persist, value, persistTo, replicateTo)(client, w, ec)
  }

  def delete(key: String)(implicit client: CouchbaseClient, ec: ExecutionContext): Future[OperationStatus] = {
    wrapJavaFutureInFuture( client.delete(key), ec )
  }

  def delete[T](key: String, replicateTo: ReplicateTo)(implicit client: CouchbaseClient, w: Writes[T], ec: ExecutionContext): Future[OperationStatus] = {
    wrapJavaFutureInFuture( client.delete(key, replicateTo), ec )
  }
  
  def delete[T](key: String, persistTo: PersistTo)(implicit client: CouchbaseClient, w: Writes[T], ec: ExecutionContext): Future[OperationStatus] = {
    wrapJavaFutureInFuture( client.delete(key, persistTo), ec )
  }
  
  def delete[T](key: String, persistTo: PersistTo, replicateTo: ReplicateTo)(implicit client: CouchbaseClient, w: Writes[T], ec: ExecutionContext): Future[OperationStatus] = {
    wrapJavaFutureInFuture( client.delete(key, persistTo, replicateTo), ec )
  }

  def replace[T](key: String, exp: Int, value: T)(implicit client: CouchbaseClient, w: Writes[T], ec: ExecutionContext): Future[OperationStatus] = {
    wrapJavaFutureInFuture( client.replace(key, exp, Json.stringify(w.writes(value))), ec )
  }

  def replace[T](key: String, exp: Int, value: T, replicateTo: ReplicateTo)(implicit client: CouchbaseClient, w: Writes[T], ec: ExecutionContext): Future[OperationStatus] = {
    wrapJavaFutureInFuture( client.replace(key, exp, value, replicateTo), ec )
  }
  
  def replace[T](key: String, value: T, replicateTo: ReplicateTo)(implicit client: CouchbaseClient, w: Writes[T], ec: ExecutionContext): Future[OperationStatus] = {
     replace(key, Polling.persist, value, replicateTo)(client, w, ec)
  }
  
  def replace[T](key: String, exp: Int, value: T, persistTo: PersistTo)(implicit client: CouchbaseClient, w: Writes[T], ec: ExecutionContext): Future[OperationStatus] = {
    wrapJavaFutureInFuture( client.replace(key, exp, value, persistTo), ec )
  }
  
  def replace[T](key: String, value: T, persistTo: PersistTo)(implicit client: CouchbaseClient, w: Writes[T], ec: ExecutionContext): Future[OperationStatus] = {
    replace(key, Polling.persist, value, persistTo)(client, w, ec)
  }
  
  def replace[T](key: String, exp: Int, value: T, persistTo: PersistTo, replicateTo: ReplicateTo)(implicit client: CouchbaseClient, w: Writes[T], ec: ExecutionContext): Future[OperationStatus] = {
    wrapJavaFutureInFuture( client.replace(key, exp, value, persistTo, replicateTo), ec )
  }
  
  def replace[T](key: String, value: T, persistTo: PersistTo, replicateTo: ReplicateTo)(implicit client: CouchbaseClient, w: Writes[T], ec: ExecutionContext): Future[OperationStatus] = {
    replace(key, Polling.persist, value, persistTo,replicateTo)(client, w, ec)
  }

  def flush(delay: Int)(implicit client: CouchbaseClient, ec: ExecutionContext): Future[OperationStatus] = {
    wrapJavaFutureInFuture( client.flush(delay), ec )
  }

  def flush()(implicit client: CouchbaseClient, ec: ExecutionContext): Future[OperationStatus] = {
     flush(Polling.persist)(client, ec)
  }

  def set[T](key: String, value: T)(implicit client: CouchbaseClient, w: Writes[T], ec: ExecutionContext): Future[OperationStatus] = {
    set(key, Polling.persist, value)(client, w, ec)
  }

  def add[T](key: String, value: T)(implicit client: CouchbaseClient, w: Writes[T], ec: ExecutionContext): Future[OperationStatus] = {
    add(key, Polling.persist, value)(client, w, ec)
  }

  def replace[T](key: String, value: T)(implicit client: CouchbaseClient, w: Writes[T], ec: ExecutionContext): Future[OperationStatus] = {
    replace(key, Polling.persist, value)(client, w, ec)
  }

  private def wrapJavaFutureInPureFuture[T](javaFuture: java.util.concurrent.Future[T], ec: ExecutionContext): Future[T] = {
    if (Polling.pollingFutures) {
      val promise = Promise[T]()
      pollJavaFutureUntilDoneOrCancelled(javaFuture, promise, ec)
      promise.future
    } else {
       Future {
         javaFuture.get
       }(ec)
    }
  }

  private def wrapJavaFutureInFuture[T](javaFuture: OperationFuture[T], ec: ExecutionContext): Future[OperationStatus] = {
    if (Polling.pollingFutures) {
      val promise = Promise[OperationStatus]()
      pollCouchbaseFutureUntilDoneOrCancelled(javaFuture, promise, ec)
      promise.future
    } else {
      Future {
        javaFuture.get
        javaFuture.getStatus
      }(ec)
    }
  }

  private def wrapJavaFutureInFuture[T](javaFuture: HttpFuture[T], ec: ExecutionContext): Future[OperationStatus] = {
    if (Polling.pollingFutures) {
      val promise = Promise[OperationStatus]()
      pollCouchbaseFutureUntilDoneOrCancelled(javaFuture, promise, ec)
      promise.future
    } else {
      Future {
        javaFuture.get
        javaFuture.getStatus
      }(ec)
    }
  }

  private def pollJavaFutureUntilDoneOrCancelled[T](javaFuture: java.util.concurrent.Future[T], promise: Promise[T], ec: ExecutionContext) {
    if (javaFuture.isDone || javaFuture.isCancelled) {
      promise.success(javaFuture.get)
    } else {
      Polling.system.scheduler.scheduleOnce(FiniteDuration(Polling.delay, TimeUnit.MILLISECONDS)) {
        pollJavaFutureUntilDoneOrCancelled(javaFuture, promise, ec)
      }(ec)
    }
  }

  private def pollCouchbaseFutureUntilDoneOrCancelled[T](javaFuture: java.util.concurrent.Future[T], promise: Promise[OperationStatus], ec: ExecutionContext) {
    if (javaFuture.isDone || javaFuture.isCancelled) {
      javaFuture match {
        case o: OperationFuture[T] => {
          o.get
          promise.success(o.getStatus)
        }
        case h: HttpFuture[T] => {
          h.get
          promise.success(h.getStatus)
        }
      }
    } else {
      Polling.system.scheduler.scheduleOnce(FiniteDuration(Polling.delay, TimeUnit.MILLISECONDS)) {
        pollCouchbaseFutureUntilDoneOrCancelled(javaFuture, promise, ec)
      }(ec)
    }
  }
}

object Polling {
  val persist: Int = -1
  val delay: Long = Play.configuration.getLong("couchbase-ec.polldelay").getOrElse(50L)
  val pollingFutures: Boolean = Play.configuration.getBoolean("couchbase-ec.pollfutures").getOrElse(false)
  val system = ActorSystem("JavaFutureToScalaFuture")
  if (pollingFutures) {
    play.api.Logger("CouchbasePlugin").info("Using polling to wait for Java Future.")
  }
}
