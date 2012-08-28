/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import akka.actor.ActorSystem
import akka.actor.DynamicAccess
import akka.actor.Scheduler
import akka.event.EventStream
import akka.remote.RemoteActorRefProvider
import akka.remote.RemoteDeployer
import akka.actor.Deploy
import com.typesafe.config.Config
import akka.ConfigurationException
import akka.actor.NoScopeGiven
import akka.routing.RemoteRouterConfig
import akka.cluster.routing.ClusterRouterConfig
import akka.actor.Scope

class ClusterActorRefProvider(
  _systemName: String,
  _settings: ActorSystem.Settings,
  _eventStream: EventStream,
  _scheduler: Scheduler,
  _dynamicAccess: DynamicAccess) extends RemoteActorRefProvider(
  _systemName, _settings, _eventStream, _scheduler, _dynamicAccess) {

  override val deployer: RemoteDeployer = new ClusterDeployer(settings, dynamicAccess)

}

private[akka] class ClusterDeployer(_settings: ActorSystem.Settings, _pm: DynamicAccess) extends RemoteDeployer(_settings, _pm) {
  override def parseConfig(path: String, config: Config): Option[Deploy] = {
    super.parseConfig(path, config) match {
      case d @ Some(deploy) ⇒
        if (deploy.config.getBoolean("cluster")) {
          if (deploy.scope != NoScopeGiven)
            throw new ConfigurationException("Cluster deployment can't be combined with scope [%s]".format(deploy.scope))
          if (deploy.routerConfig.isInstanceOf[RemoteRouterConfig])
            throw new ConfigurationException("Cluster deployment can't be combined with [%s]".format(deploy.routerConfig))
          Some(deploy.copy(routerConfig = ClusterRouterConfig(deploy.routerConfig)))
        } else d
      case None ⇒ None
    }
  }
}

@SerialVersionUID(1L)
abstract class ClusterScope extends Scope

/**
 * Cluster aware scope of a [[akka.actor.Deploy]]
 */
case object ClusterScope extends ClusterScope {
  /**
   * Java API: get the singleton instance
   */
  def getInstance = this

  def withFallback(other: Scope): Scope = this
}
