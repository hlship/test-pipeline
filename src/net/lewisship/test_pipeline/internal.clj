; Copyright (c) 2022-present Howard Lewis Ship.
;
; Licensed under the Apache License, Version 2.0 (the "License")
; you may not use this file except in compliance with the License.
; You may obtain a copy of the License at
;
;     http://www.apache.org/licenses/LICENSE-2.0
;
; Unless required by applicable law or agreed to in writing, software
; distributed under the License is distributed on an "AS IS" BASIS,
; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
; See the License for the specific language governing permissions and
; limitations under the License.

(ns ^:no-doc net.lewisship.test-pipeline.internal
  (:require [clojure.tools.logging.impl :as impl]))

(defn get-and-clear!
  "Gets the value of an atom, then resets it to the empty list."
  [*atom]
  (loop []
    (let [result @*atom]
      (if (compare-and-set! *atom result [])
        result
        (recur)))))

(defrecord CaptureLogger [logger-name *log-events]

  impl/Logger

  (enabled? [_ _] true)

  (write! [_ level throwable message]
    (let [event {:thread-name (-> (Thread/currentThread) .getName)
                 :logger logger-name
                 :level level
                 :throwable throwable
                 :message message}]
      (swap! *log-events conj event))
    nil))

(defrecord CaptureLoggerFactory [*log-events *loggers]

  impl/LoggerFactory
  (name [_] "com.walmartlabs/test-fixtures capture")

  (get-logger [_ logger-ns]
    (loop []
      (let [loggers @*loggers
            logger (get loggers logger-ns)]
        (or logger
          (let [new-logger (->CaptureLogger (str logger-ns) *log-events)
                loggers' (assoc loggers logger-ns new-logger)]
            (if (compare-and-set! *loggers loggers loggers')
              new-logger
              (recur))))))))


(defn capture-logger-factory
  [*log-events]
  (->CaptureLoggerFactory *log-events (atom {})))

