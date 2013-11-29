/*
 * gretty
 *
 * Copyright 2013  Andrey Hihlovskiy.
 *
 * See the file "license.txt" for copying and usage permission.
 */
package org.akhikhl.gretty

import org.gradle.api.*
import org.gradle.api.plugins.*
import org.gradle.api.tasks.*
import org.gradle.api.tasks.bundling.*

final class Runner {

  private static class RealmInfo {
    String realm
    String realmConfigFile
  }

  static void sendServiceCommand(int servicePort, String command) {
    Socket s = new Socket(InetAddress.getByName('127.0.0.1'), servicePort)
    try {
      OutputStream out = s.getOutputStream()
      System.out.println "Sending command: ${command}"
      out.write(("${command}\n").getBytes())
      out.flush()
    } finally {
      s.close()
    }
  }

  final Project project
  final Map params
  def helper
  def server
  def scanner

  Runner(Map params, Project project) {
    this.project = project
    this.params = params
  }

  void consoleStart() {
    startServer()

    Thread monitor = new MonitorThread(this)
    monitor.start()

    onStart()

    System.out.println 'Jetty server started.'
    System.out.println 'You can see web-application in browser under the address:'
    System.out.println "http://localhost:${project.gretty.port}${contextPath}"

    if(params.interactive)
      System.out.println 'Press any key to stop the jetty server.'
    else
      System.out.println 'Enter \'gradle jettyStop\' to stop the jetty server.'
    System.out.println()

    if(params.interactive) {
      System.in.read()
      if(monitor.running)
        sendServiceCommand project.gretty.servicePort, 'stop'
    }

    monitor.join()

    System.out.println 'Jetty server stopped.'
    onStop()
  }

  void startServer() {
    assert helper == null
    assert server == null

    List urls = []
    urls.addAll project.configurations.grettyConfig.collect { it.toURI().toURL() }
    if(params.inplace) {
      urls.addAll project.sourceSets.main.runtimeClasspath.files.collect { it.toURI().toURL() }
      // ATTENTION: order of overlays is important!
      for(String overlay in project.gretty.overlays.reverse())
        urls.addAll project.project(overlay).sourceSets.main.runtimeClasspath.files.collect { it.toURI().toURL() }
    }
    ClassLoader classLoader = new URLClassLoader(urls as URL[])

    helper = classLoader.findClass('org.akhikhl.gretty.GrettyHelper')

    server = helper.createServer()
    server.setConnectors helper.createConnectors(project.gretty.port)

    def context = helper.createWebAppContext()
    helper.setClassLoader(context, classLoader)

    RealmInfo realmInfo = getRealmInfo()
    if(realmInfo.realm && realmInfo.realmConfigFile)
      context.getSecurityHandler().setLoginService(helper.createLoginService(realmInfo.realm, realmInfo.realmConfigFile))

    String contextPath = getContextPath()
    context.contextPath = contextPath

    getInitParameters().each { key, value ->
      context.setInitParameter key, value
    }

    if(params.inplace)
      context.setResourceBase "${project.buildDir}/webapp"
    else
      context.setWar project.tasks.war.archivePath.toString()

    context.server = server
    server.handler = context

    server.start()

    if(params.inplace)
      setupInplaceScanner()
  }

  void stopServer() {
    if(scanner != null) {
      project.logger.trace 'Stopping scanner {}', scanner
      scanner.stop()
      scanner = null
    }
    server.stop()
    server = null
    helper = null
  }

  private String getContextPath() {
    String contextPath = project.gretty.contextPath
    if(!contextPath)
      for(def overlay in project.gretty.overlays.reverse()) {
        overlay = project.project(overlay)
        if(overlay.gretty.contextPath) {
          contextPath = overlay.gretty.contextPath
          break
        }
      }
    contextPath = contextPath ?: "/${project.name}"
    return contextPath
  }

  private Map getInitParameters() {
    Map initParams = [:]
    for(def overlay in project.gretty.overlays) {
      overlay = project.project(overlay)
      for(def e in overlay.gretty.initParameters) {
        def paramValue = e.value
        if(paramValue instanceof Closure)
          paramValue = paramValue()
        initParams[e.key] = paramValue
      }
    }
    for(def e in project.gretty.initParameters) {
      def paramValue = e.value
      if(paramValue instanceof Closure)
        paramValue = paramValue()
      initParams[e.key] = paramValue
    }
    return initParams
  }

  private RealmInfo getRealmInfo() {
    String realm = project.gretty.realm
    String realmConfigFile = project.gretty.realmConfigFile
    if(realmConfigFile && !new File(realmConfigFile).isAbsolute())
      realmConfigFile = "${project.webAppDir.absolutePath}/${realmConfigFile}"
    if(!realm || !realmConfigFile)
      for(def overlay in project.gretty.overlays.reverse()) {
        overlay = project.project(overlay)
        if(overlay.gretty.realm && overlay.gretty.realmConfigFile) {
          realm = overlay.gretty.realm
          realmConfigFile = overlay.gretty.realmConfigFile
          if(realmConfigFile && !new File(realmConfigFile).isAbsolute())
            realmConfigFile = "${overlay.webAppDir.absolutePath}/${realmConfigFile}"
          break
        }
      }
    return new RealmInfo(realm: realm, realmConfigFile: realmConfigFile)
  }

  private void onStart() {
    for(def overlay in project.gretty.overlays) {
      overlay = project.project(overlay)
      overlay.gretty.onStart.each { onStart ->
        if(onStart instanceof Closure)
          onStart()
      }
    }
    project.gretty.onStart.each { onStart ->
      if(onStart instanceof Closure)
        onStart()
    }
  }

  private void onStop() {
    project.gretty.onStop.each { onStop ->
      if(onStop instanceof Closure)
        onStop()
    }
    for(def overlay in project.gretty.overlays.reverse()) {
      overlay = project.project(overlay)
      overlay.gretty.onStop.each { onStop ->
        if(onStop instanceof Closure)
          onStop()
      }
    }
  }

  private setupInplaceScanner() {
    List scanDirs = []
    scanDirs.addAll project.sourceSets.main.runtimeClasspath.files
    // ATTENTION: order of overlays is important!
    for(String overlay in project.gretty.overlays.reverse())
      scanDirs.addAll project.project(overlay).sourceSets.main.runtimeClasspath.files
    for(File f in scanDirs)
      project.logger.trace 'scanDir: {}', f
    scanner = helper.createScanner()
    scanner.reportDirs = true
    scanner.reportExistingFilesOnStartup = false
    scanner.scanInterval = 5
    scanner.recursive = true
    scanner.scanDirs = scanDirs
    helper.addScannerScanCycleListener scanner, { started, cycle ->
      project.logger.trace 'ScanCycleListener started={}, cycle={}', started, cycle
    }
    helper.addScannerBulkListener scanner, { changedFiles ->
      project.logger.trace 'BulkListener changedFiles={}', changedFiles
      sendServiceCommand project.gretty.servicePort, 'restart'
    }
    project.logger.trace 'Starting scanner {}', scanner
    scanner.start()
    return scanner
  }
}