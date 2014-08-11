(ns onyx.coordinator.generative.coordinator-generative-test
  (:require [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :as ct]
            [midje.sweet :refer :all]
            [onyx.coordinator.impl :as impl]))

(def never-seal-completed-tasks
  (prop/for-all [same-task? gen/boolean
                 current-state (gen/elements [:idle :acking :active :waiting :sealing :dead])
                 idle gen/pos-int
                 acking gen/pos-int
                 active gen/pos-int
                 waiting gen/pos-int
                 sealing gen/pos-int
                 dead gen/pos-int]
                (let [complete? true
                      result (impl/seal-resource?*
                              complete?
                              same-task?
                              current-state
                              {:idle idle
                               :acking acking
                               :active active
                               :waiting waiting
                               :sealing sealing
                               :dead dead})]
                  (false? (:seal? result)))))

(def never-seal-old-tasks
  (prop/for-all [complete? gen/boolean
                 current-state (gen/elements [:idle :acking :active :waiting :sealing :dead])
                 idle gen/pos-int
                 acking gen/pos-int
                 active gen/pos-int
                 waiting gen/pos-int
                 sealing gen/pos-int
                 dead gen/pos-int]
                (let [same-task? false]
                  (false? (:seal? (impl/seal-resource?*
                                   complete?
                                   same-task?
                                   current-state
                                   {:idle idle
                                    :acking acking
                                    :active active
                                    :waiting waiting
                                    :sealing sealing
                                    :dead dead}))))))

(def sealing-peers-never-change-state
  (prop/for-all [same-task? gen/boolean
                 idle gen/pos-int
                 acking gen/pos-int
                 active gen/pos-int
                 waiting gen/pos-int
                 sealing gen/pos-int
                 dead gen/pos-int]
                (let [complete? false
                      current-state :sealing]
                  (false? (:update-state?
                           (impl/seal-resource?*
                            complete?
                            same-task?
                            current-state
                            {:idle idle
                             :acking acking
                             :active active
                             :waiting waiting
                             :sealing sealing
                             :dead dead}))))))

(def active-peers-transition-to-waiting-or-sealing
  (prop/for-all [idle gen/pos-int
                 acking gen/pos-int
                 active gen/pos-int
                 waiting gen/pos-int
                 sealing gen/pos-int
                 dead gen/pos-int]
                (let [complete? false
                      same-task? true
                      current-state :active
                      result
                      (:next-state
                       (impl/seal-resource?*
                        complete?
                        same-task?
                        current-state
                        {:idle idle
                         :acking acking
                         :active active
                         :waiting waiting
                         :sealing sealing
                         :dead dead}))]
                  (or (= result :waiting)
                      (= result :sealing)))))

(prn (tc/quick-check 100000 never-seal-completed-tasks))
(prn (tc/quick-check 100000 never-seal-old-tasks))
(prn (tc/quick-check 100000 sealing-peers-never-change-state))
(prn (tc/quick-check 100000 active-peers-transition-to-waiting-or-sealing))

;;; Tests for update-state