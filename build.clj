(ns build
  (:require [clojure.tools.build.api :as b]
            [net.lewisship.build :refer [requiring-invoke] :as nb]))

(def lib 'io.github.hlship/test-pipeline)
(def version "0.6")

(def options {:project-name lib
              :version version})

(defn clean
  [_params]
  (b/delete {:path "target"}))

(defn jar
  [_params]
  (nb/create-jar options))

(defn deploy
  [_params]
  (clean nil)
  (jar nil)
  (nb/deploy-jar (nb/create-jar options)))

(defn codox
  [_params]
  (nb/generate-codox options))
