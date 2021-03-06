/*
 * Gretty
 *
 * Copyright (C) 2013-2014 Andrey Hihlovskiy and contributors.
 *
 * See the file "LICENSE" for copying and usage permission.
 * See the file "CONTRIBUTORS" for complete list of contributors.
 */
package org.akhikhl.gretty

/**
 *
 * @author akhikhl
 */
class GrettyExtension extends GrettyConfig {

  protected List overlays = []

  String integrationTestTask = 'integrationTest'

  protected afterEvaluate = []

  Closure webappCopy = {}

  void afterEvaluate(Closure closure) {
    afterEvaluate.add(closure)
  }

  void overlay(def newValue) {
    if(!(newValue instanceof String))
      throw new Exception("Overlay ${newValue?.toString()} should be a string")
    overlays.add newValue
  }
}
