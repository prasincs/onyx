(ns ^:no-doc onyx.peer.task-lifecycle
    (:require [clojure.core.async :refer [alts!! <!! >!! chan close! thread go]]
              [com.stuartsierra.component :as component]
              [dire.core :as dire]
              [taoensso.timbre :refer [info warn] :as timbre]
              [onyx.coordinator.planning :refer [find-task]]
              [onyx.peer.task-lifecycle-extensions :as l-ext]
              [onyx.peer.pipeline-extensions :as p-ext]
              [onyx.queue.hornetq :refer [hornetq]]
              [onyx.peer.transform :as transform]
              [onyx.peer.group :as group]
              [onyx.peer.aggregate :as aggregate]
              [onyx.extensions :as extensions]
              [onyx.plugin.hornetq]))

(defn munge-start-lifecycle [event]
  (l-ext/start-lifecycle?* event))

(defn new-payload [sync peer-node payload-ch]
  (let [peer-contents (extensions/read-node sync peer-node)
        node (:node (extensions/create sync :payload))
        updated-contents (assoc peer-contents :payload-node node)]
    (extensions/write-node sync peer-node updated-contents)
    node))

(defn munge-inject-temporal [event]
  (let [cycle-params {:onyx.core/lifecycle-id (java.util.UUID/randomUUID)}
        rets (l-ext/inject-temporal-resources* event)]
    (if-not (:onyx.core/session rets)
      (let [session (extensions/create-tx-session (:onyx.core/queue event))]
        (merge event rets cycle-params {:onyx.core/session session}))
      (merge event cycle-params rets))))

(defn munge-read-batch [event]
  (merge event (p-ext/read-batch event)))

(defn munge-decompress-batch [event]
  (merge event (p-ext/decompress-batch event)))

(defn munge-strip-sentinel [event]
  (merge event (p-ext/strip-sentinel event)))

(defn munge-requeue-sentinel [{:keys [onyx.core/requeue?] :as event}]
  (if requeue?
    (merge event (p-ext/requeue-sentinel event))
    event))

(defn munge-apply-fn [{:keys [onyx.core/decompressed] :as event}]
  (if (seq decompressed)
    (merge event (p-ext/apply-fn event))
    (merge event {:onyx.core/results []})))

(defn munge-compress-batch [event]
  (merge event (p-ext/compress-batch event)))

(defn munge-write-batch [event]
  (merge event (p-ext/write-batch event)))

(defn munge-status-check [{:keys [onyx.core/sync onyx.core/status-node] :as event}]
  (assoc event :onyx.core/commit? (extensions/node-exists? sync status-node)))

(defn munge-ack [{:keys [onyx.core/queue onyx.core/batch onyx.core/commit?] :as event}]
  (if commit?
    (merge event (p-ext/ack-batch event))
    event))

(defn munge-commit-tx
  [{:keys [onyx.core/queue onyx.core/session onyx.core/commit?] :as event}]
  (if commit?
    (do (extensions/commit-tx queue session)
        (assoc event :onyx.core/committed true))
    event))

(defn munge-close-temporal-resources [event]
  (merge event (l-ext/close-temporal-resources* event)))

(defn munge-close-resources
  [{:keys [onyx.core/queue onyx.core/session onyx.core/producers
           onyx.core/consumers onyx.core/reserve?] :as event}]
  (doseq [producer producers] (extensions/close-resource queue producer))
  (doseq [consumer consumers] (extensions/close-resource queue consumer))
  (when-not reserve?
    (extensions/close-resource queue session))
  (assoc event :onyx.core/closed? true))

(defn munge-new-payload
  [{:keys [onyx.core/sync onyx.core/peer-node
           onyx.core/peer-version onyx.core/payload-ch] :as event}]
  (if (= (extensions/version sync peer-node) peer-version)
    (let [node (new-payload sync peer-node payload-ch)]
      (extensions/on-change sync node #(>!! payload-ch %))
      (assoc event :onyx.core/new-payload-node node))
    event))

(defn munge-seal-resource
  [{:keys [onyx.core/sync onyx.core/exhaust-node onyx.core/seal-node
           onyx.core/pipeline-state] :as event}]
  (let [state @pipeline-state]
    (if (:tried-to-seal? state)
      (merge event {:onyx.core/sealed? false})
      (let [seal-response-ch (chan)]
        (extensions/on-change sync seal-node #(>!! seal-response-ch %))
        (extensions/touch-node sync exhaust-node)
        (let [response (<!! seal-response-ch)]
          (let [path (:path response)
                {:keys [seal?]} (extensions/read-node sync path)]
            (swap! pipeline-state assoc :tried-to-seal? true)
            (if seal?
              (merge event (p-ext/seal-resource event) {:onyx.core/sealed? true})
              (merge event {:onyx.core/sealed? true}))))))))

(defn munge-complete-task
  [{:keys [onyx.core/sync onyx.core/completion-node
           onyx.core/cooldown-node onyx.core/pipeline-state onyx.core/sealed?]
    :as event}]
  (let [state @pipeline-state]
    (let [complete-response-ch (chan)]
      (extensions/on-change sync cooldown-node #(>!! complete-response-ch %))
      (extensions/touch-node sync completion-node)
      (let [response (<!! complete-response-ch)]
        (let [path (:path response)
              result (extensions/read-node sync path)]
          (merge event {:onyx.core/complete-success? true}))))))

(defn inject-temporal-loop [read-ch kill-ch pipeline-data dead-ch]
  (loop []
    (when (first (alts!! [kill-ch] :default true))
      (>!! read-ch (munge-inject-temporal pipeline-data))
      (recur))))

(defn read-batch-loop [read-ch decompress-ch dead-ch]
  (loop []
    (when-let [event (<!! read-ch)]
      (>!! decompress-ch (munge-read-batch event))
      (recur))))

(defn decompress-batch-loop [decompress-ch strip-ch dead-ch]
  (loop []
    (when-let [event (<!! decompress-ch)]
      (>!! strip-ch (munge-decompress-batch event))
      (recur))))

(defn strip-sentinel-loop [strip-ch requeue-ch dead-ch]
  (loop []
    (when-let [event (<!! strip-ch)]
      (>!! requeue-ch (munge-strip-sentinel event))
      (recur))))

(defn requeue-sentinel-loop [requeue-ch apply-fn-ch dead-ch]
  (loop []
    (when-let [event (<!! requeue-ch)]
      (>!! apply-fn-ch (munge-requeue-sentinel event))
      (recur))))

(defn apply-fn-loop [apply-fn-ch compress-ch dead-ch]
  (loop []
    (when-let [event (<!! apply-fn-ch)]
      (>!! compress-ch (munge-apply-fn event))
      (recur))))

(defn compress-batch-loop [compress-ch write-batch-ch dead-ch]
  (loop []
    (when-let [event (<!! compress-ch)]
      (>!! write-batch-ch (munge-compress-batch event))
      (recur))))

(defn write-batch-loop [write-ch status-check-ch dead-ch]
  (loop []
    (when-let [event (<!! write-ch)]
      (>!! status-check-ch (munge-write-batch event))
      (recur))))

(defn status-check-loop [status-ch ack-ch dead-ch]
  (loop []
    (when-let [event (<!! status-ch)]
      (>!! ack-ch (munge-status-check event))
      (recur))))

(defn ack-loop [ack-ch commit-ch dead-ch]
  (loop []
    (when-let [event (<!! ack-ch)]
      (>!! commit-ch (munge-ack event))
      (recur))))

(defn commit-tx-loop [commit-ch close-resources-ch dead-ch]
  (loop []
    (when-let [event (<!! commit-ch)]
      (>!! close-resources-ch (munge-commit-tx event))
      (recur))))

(defn close-resources-loop [close-ch close-temporal-ch dead-ch]
  (loop []
    (when-let [event (<!! close-ch)]
      (>!! close-temporal-ch (munge-close-resources event))
      (recur))))

(defn close-temporal-loop [close-temporal-ch reset-payload-ch dead-ch]
  (loop []
    (when-let [event (<!! close-temporal-ch)]
      (>!! reset-payload-ch (munge-close-temporal-resources event))
      (recur))))

(defn reset-payload-node-loop [reset-ch seal-ch dead-ch]
  (loop []
    (when-let [event (<!! reset-ch)]
      (if (and (:onyx.core/tail-batch? event) (:onyx.core/commit? event))
        (let [event (munge-new-payload event)]
          (>!! seal-ch event))
        (>!! seal-ch event))
      (recur))))

(defn seal-resource-loop [seal-ch internal-complete-ch dead-ch]
  (loop []
    (when-let [event (<!! seal-ch)]
      (if (:onyx.core/tail-batch? event)
        (>!! internal-complete-ch (munge-seal-resource event))
        (>!! internal-complete-ch event))
      (recur))))

(defn complete-task-loop [complete-ch dead-ch]
  (loop []
    (when-let [event (<!! complete-ch)]
      (when (and (:onyx.core/tail-batch? event)
                 (:onyx.core/commit? event)
                 (:onyx.core/sealed? event))
        (let [event (munge-complete-task event)]
          (when (:onyx.core/complete-success? event)
            (>!! (:onyx.core/complete-ch event) true))))
      (recur))))

(defrecord TaskLifeCycle [id payload sync queue payload-ch complete-ch err-ch opts]
  component/Lifecycle

  (start [component]
    (taoensso.timbre/info (format "[%s] Starting Task LifeCycle for %s" id (:task/name (:task payload))))

    (let [open-session-kill-ch (chan 0)
          read-batch-ch (chan 0)
          decompress-batch-ch (chan 0)
          strip-sentinel-ch (chan 0)
          requeue-sentinel-ch (chan 0)
          apply-fn-ch (chan 0)
          compress-batch-ch (chan 0)
          write-batch-ch (chan 0)
          status-check-ch (chan 0)
          ack-ch (chan 0)
          commit-tx-ch (chan 0)
          close-resources-ch (chan 0)
          close-temporal-ch (chan 0)
          reset-payload-node-ch (chan 0)
          seal-ch (chan 0)
          complete-task-ch (chan 0)

          open-session-dead-ch (chan)
          read-batch-dead-ch (chan)
          decompress-batch-dead-ch (chan)
          strip-sentinel-dead-ch (chan)
          requeue-sentinel-dead-ch (chan)
          apply-fn-dead-ch (chan)
          compress-batch-dead-ch (chan)
          write-batch-dead-ch (chan)
          status-check-dead-ch (chan)
          ack-dead-ch (chan)
          commit-tx-dead-ch (chan)
          close-resources-dead-ch (chan)
          close-temporal-dead-ch (chan)
          reset-payload-node-dead-ch (chan)
          seal-dead-ch (chan)
          complete-task-dead-ch (chan)

          task (:task/name (:task payload))
          catalog (extensions/read-node sync (:node/catalog (:nodes payload)))
          ingress-queues (:task/ingress-queues (:task payload))
          ingress-queues (if-not (coll? ingress-queues) (vector ingress-queues) ingress-queues)

          pipeline-data {:onyx.core/id id
                         :onyx.core/task task
                         :onyx.core/catalog catalog
                         :onyx.core/task-map (find-task catalog task)
                         :onyx.core/ingress-queues ingress-queues
                         :onyx.core/egress-queues (:task/egress-queues (:task payload))
                         :onyx.core/peer-node (:node/peer (:nodes payload))
                         :onyx.core/status-node (:node/status (:nodes payload))
                         :onyx.core/exhaust-node (:node/exhaust (:nodes payload))
                         :onyx.core/seal-node (:node/seal (:nodes payload))
                         :onyx.core/completion-node (:node/completion (:nodes payload))
                         :onyx.core/cooldown-node (:node/cooldown (:nodes payload))
                         :onyx.core/task-node (:node/task (:nodes payload))
                         :onyx.core/workflow (extensions/read-node sync (:node/workflow (:nodes payload)))
                         :onyx.core/peer-version (extensions/version sync (:node/peer (:nodes payload)))
                         :onyx.core/payload-ch payload-ch
                         :onyx.core/complete-ch complete-ch
                         :onyx.core/params (or (get (:fn-params opts) task) [])
                         :onyx.core/queue queue
                         :onyx.core/sync sync
                         :onyx.core/pipeline-state (atom {:tried-to-seal? false})}

          pipeline-data (assoc pipeline-data :onyx.core/queue (extensions/optimize-concurrently queue pipeline-data))
          pipeline-data (merge pipeline-data (l-ext/inject-lifecycle-resources* pipeline-data))]

      (dire/with-handler! #'inject-temporal-loop
        java.lang.Exception
        (fn [e & _]
          (warn e)
          (go (>!! err-ch true))))

      (dire/with-handler! #'read-batch-loop
        java.lang.Exception
        (fn [e & _]
          (warn e)
          (go (>!! err-ch true))))

      (dire/with-handler! #'decompress-batch-loop
        java.lang.Exception
        (fn [e & _]
          (warn e)
          (go (>!! err-ch true))))

      (dire/with-handler! #'strip-sentinel-loop
        java.lang.Exception
        (fn [e & _]
          (warn e)
          (go (>!! err-ch true))))

      (dire/with-handler! #'requeue-sentinel-loop
        java.lang.Exception
        (fn [e & _]
          (warn e)
          (go (>!! err-ch true))))

      (dire/with-handler! #'apply-fn-loop
        java.lang.Exception
        (fn [e & _]
          (warn e)
          (go (>!! err-ch true))))

      (dire/with-handler! #'compress-batch-loop
        java.lang.Exception
        (fn [e & _]
          (warn e)
          (go (>!! err-ch true))))

      (dire/with-handler! #'write-batch-loop
        java.lang.Exception
        (fn [e & _]
          (warn e)
          (go (>!! err-ch true))))

      (dire/with-handler! #'status-check-loop
        java.lang.Exception
        (fn [e & _]
          (warn e)
          (go (>!! err-ch true))))

      (dire/with-handler! #'ack-loop
        java.lang.Exception
        (fn [e & _]
          (warn e)
          (go (>!! err-ch true))))

      (dire/with-handler! #'commit-tx-loop
        java.lang.Exception
        (fn [e & _]
          (warn e)
          (go (>!! err-ch true))))

      (dire/with-handler! #'close-resources-loop
        java.lang.Exception
        (fn [e & _]
          (warn e)
          (go (>!! err-ch true))))
      
      (dire/with-handler! #'close-temporal-loop
        java.lang.Exception
        (fn [e & _]
          (warn e)
          (go (>!! err-ch true))))

      (dire/with-handler! #'reset-payload-node-loop
        java.lang.Exception
        (fn [e & _]
          (warn e)
          (go (>!! err-ch true))))

      (dire/with-handler! #'seal-resource-loop
        java.lang.Exception
        (fn [e & _]
          (warn e)
          (go (>!! err-ch true))))

      (dire/with-handler! #'complete-task-loop
        java.lang.Exception
        (fn [e & _]
          (warn e)
          (go (>!! err-ch true))))

      (dire/with-finally! #'inject-temporal-loop
        (fn [& args]
          (>!! (last args) true)))

      (dire/with-finally! #'read-batch-loop
        (fn [& args]
          (>!! (last args) true)))

      (dire/with-finally! #'decompress-batch-loop
        (fn [& args]
          (>!! (last args) true)))

      (dire/with-finally! #'strip-sentinel-loop
        (fn [& args]
          (>!! (last args) true)))

      (dire/with-finally! #'requeue-sentinel-loop
        (fn [& args]
          (>!! (last args) true)))

      (dire/with-finally! #'apply-fn-loop
        (fn [& args]
          (>!! (last args) true)))

      (dire/with-finally! #'compress-batch-loop
        (fn [& args]
          (>!! (last args) true)))

      (dire/with-finally! #'write-batch-loop
        (fn [& args]
          (>!! (last args) true)))

      (dire/with-finally! #'status-check-loop
        (fn [& args]
          (>!! (last args) true)))

      (dire/with-finally! #'ack-loop
        (fn [& args]
          (>!! (last args) true)))

      (dire/with-finally! #'commit-tx-loop
        (fn [& args]
          (>!! (last args) true)))

      (dire/with-finally! #'close-resources-loop
        (fn [& args]
          (>!! (last args) true)))

      (dire/with-finally! #'close-temporal-loop
        (fn [& args]
          (>!! (last args) true)))

      (dire/with-finally! #'reset-payload-node-loop
        (fn [& args]
          (>!! (last args) true)))

      (dire/with-finally! #'seal-resource-loop
        (fn [& args]
          (>!! (last args) true)))

      (dire/with-finally! #'complete-task-loop
        (fn [& args]
          (>!! (last args) true)))

      (while (not (:onyx.core/start-lifecycle? (munge-start-lifecycle pipeline-data)))
        (Thread/sleep (or (:back-off opts) 2000)))
      
      (assoc component
        :open-session-kill-ch open-session-kill-ch
        :read-batch-ch read-batch-ch
        :decompress-batch-ch decompress-batch-ch
        :strip-sentinel-ch strip-sentinel-ch
        :requeue-sentinel-ch requeue-sentinel-ch
        :apply-fn-ch apply-fn-ch
        :compress-batch-ch compress-batch-ch
        :write-batch-ch write-batch-ch
        :status-check-ch status-check-ch
        :ack-ch ack-ch
        :commit-tx-ch commit-tx-ch
        :close-resources-ch close-resources-ch
        :close-temporal-ch close-temporal-ch
        :reset-payload-node-ch reset-payload-node-ch
        :seal-ch seal-ch
        :complete-task-ch complete-task-ch

        :open-session-dead-ch open-session-dead-ch
        :read-batch-dead-ch read-batch-dead-ch
        :decompress-batch-dead-ch decompress-batch-dead-ch
        :strip-sentinel-dead-ch strip-sentinel-dead-ch
        :requeue-sentinel-dead-ch requeue-sentinel-dead-ch
        :apply-fn-dead-ch apply-fn-dead-ch
        :compress-batch-dead-ch compress-batch-dead-ch
        :write-batch-dead-ch write-batch-dead-ch
        :status-check-dead-ch status-check-dead-ch
        :ack-dead-ch ack-dead-ch
        :commit-tx-dead-ch commit-tx-dead-ch
        :close-resources-dead-ch close-resources-dead-ch
        :close-temporal-dead-ch close-temporal-dead-ch
        :reset-payload-node-dead-ch reset-payload-node-dead-ch
        :seal-dead-ch seal-dead-ch
        :complete-task-dead-ch complete-task-dead-ch
        
        :inject-temporal-loop (thread (inject-temporal-loop read-batch-ch open-session-kill-ch pipeline-data open-session-dead-ch))
        :read-batch-loop (thread (read-batch-loop read-batch-ch decompress-batch-ch read-batch-dead-ch))
        :decompress-batch-loop (thread (decompress-batch-loop decompress-batch-ch strip-sentinel-ch decompress-batch-dead-ch))
        :strip-sentinel-loop (thread (strip-sentinel-loop strip-sentinel-ch requeue-sentinel-ch strip-sentinel-dead-ch))
        :requeue-sentinel-loop (thread (requeue-sentinel-loop requeue-sentinel-ch apply-fn-ch requeue-sentinel-dead-ch))
        :apply-fn-loop (thread (apply-fn-loop apply-fn-ch compress-batch-ch apply-fn-dead-ch))
        :compress-batch-loop (thread (compress-batch-loop compress-batch-ch write-batch-ch compress-batch-dead-ch))
        :write-batch-loop (thread (write-batch-loop write-batch-ch status-check-ch write-batch-dead-ch))
        :status-check-loop (thread (status-check-loop status-check-ch ack-ch status-check-dead-ch))
        :ack-loop (thread (ack-loop ack-ch commit-tx-ch ack-dead-ch))
        :commit-tx-loop (thread (commit-tx-loop commit-tx-ch close-resources-ch commit-tx-dead-ch))
        :close-resources-loop (thread (close-resources-loop close-resources-ch close-temporal-ch close-resources-dead-ch))
        :close-temporal-loop (thread (close-temporal-loop close-temporal-ch reset-payload-node-ch close-temporal-dead-ch))
        :reset-payload-node-loop (thread (reset-payload-node-loop reset-payload-node-ch seal-ch reset-payload-node-dead-ch))
        :seal-resource-loop (thread (seal-resource-loop seal-ch complete-task-ch seal-dead-ch))
        :complete-task-loop (thread (complete-task-loop complete-task-ch complete-task-dead-ch))

        :pipeline-data pipeline-data)))

  (stop [component]
    (taoensso.timbre/info (format "[%s] Stopping Task LifeCycle for %s" id (:task/name (:task payload))))

    (close! (:open-session-kill-ch component))
    (<!! (:open-session-dead-ch component))

    (close! (:read-batch-ch component))
    (<!! (:read-batch-dead-ch component))

    (close! (:decompress-batch-ch component))
    (<!! (:decompress-batch-dead-ch component))

    (close! (:strip-sentinel-ch component))
    (<!! (:strip-sentinel-dead-ch component))

    (close! (:requeue-sentinel-ch component))
    (<!! (:requeue-sentinel-dead-ch component))

    (close! (:apply-fn-ch component))
    (<!! (:apply-fn-dead-ch component))

    (close! (:compress-batch-ch component))
    (<!! (:compress-batch-dead-ch component))

    (close! (:write-batch-ch component))
    (<!! (:write-batch-dead-ch component))

    (close! (:status-check-ch component))
    (<!! (:status-check-dead-ch component))

    (close! (:ack-ch component))
    (<!! (:ack-dead-ch component))

    (close! (:commit-tx-ch component))
    (<!! (:commit-tx-dead-ch component))

    (close! (:close-resources-ch component))
    (<!! (:close-resources-dead-ch component))
    
    (close! (:close-temporal-ch component))
    (<!! (:close-temporal-dead-ch component))

    (close! (:reset-payload-node-ch component))
    (<!! (:reset-payload-node-dead-ch component))

    (close! (:seal-ch component))
    (<!! (:seal-dead-ch component))

    (close! (:complete-task-ch component))
    (<!! (:complete-task-dead-ch component))

    (close! (:open-session-dead-ch component))
    (close! (:read-batch-dead-ch component))
    (close! (:decompress-batch-dead-ch component))
    (close! (:strip-sentinel-dead-ch component))
    (close! (:requeue-sentinel-dead-ch component))
    (close! (:apply-fn-dead-ch component))
    (close! (:compress-batch-dead-ch component))
    (close! (:write-batch-dead-ch component))
    (close! (:status-check-dead-ch component))
    (close! (:ack-dead-ch component))
    (close! (:commit-tx-dead-ch component))
    (close! (:close-resources-dead-ch component))
    (close! (:close-temporal-dead-ch component))
    (close! (:reset-payload-node-dead-ch component))
    (close! (:seal-dead-ch component))
    (close! (:complete-task-dead-ch component))

    (l-ext/close-lifecycle-resources* (:pipeline-data component))

    component))

(defn task-lifecycle [id payload sync queue payload-ch complete-ch err-ch opts]
  (map->TaskLifeCycle {:id id :payload payload :sync sync
                       :queue queue :payload-ch payload-ch
                       :complete-ch complete-ch :err-ch err-ch :opts opts}))

(dire/with-post-hook! #'munge-start-lifecycle
  (fn [{:keys [onyx.core/id onyx.core/lifecycle-id onyx.core/start-lifecycle?] :as event}]
    (when-not start-lifecycle?
      (timbre/info (format "[%s / %s] Sequential task currently has queue consumers. Backing off and retrying..." id lifecycle-id)))))

(dire/with-post-hook! #'munge-inject-temporal
  (fn [{:keys [onyx.core/id onyx.core/lifecycle-id]}]
    (taoensso.timbre/info (format "[%s / %s] Created new tx session" id lifecycle-id))))

(dire/with-post-hook! #'munge-read-batch
  (fn [{:keys [onyx.core/id onyx.core/batch onyx.core/lifecycle-id]}]
    (taoensso.timbre/info (format "[%s / %s] Read %s segments" id lifecycle-id (count batch)))))

(dire/with-post-hook! #'munge-strip-sentinel
  (fn [{:keys [onyx.core/id onyx.core/decompressed onyx.core/lifecycle-id]}]
    (taoensso.timbre/info (format "[%s / %s] Attempted to strip sentinel. %s segments left" id lifecycle-id (count decompressed)))))

(dire/with-post-hook! #'munge-requeue-sentinel
  (fn [{:keys [onyx.core/id onyx.core/tail-batch? onyx.core/lifecycle-id]}]
    (taoensso.timbre/info (format "[%s / %s] Requeued sentinel value" id lifecycle-id))))

(dire/with-post-hook! #'munge-decompress-batch
  (fn [{:keys [onyx.core/id onyx.core/decompressed onyx.core/batch onyx.core/lifecycle-id]}]
    (taoensso.timbre/info (format "[%s / %s] Decompressed %s segments" id lifecycle-id (count decompressed)))))

(dire/with-post-hook! #'munge-apply-fn
  (fn [{:keys [onyx.core/id onyx.core/results onyx.core/lifecycle-id]}]
    (taoensso.timbre/info (format "[%s / %s] Applied fn to %s segments" id lifecycle-id (count results)))))

(dire/with-post-hook! #'munge-compress-batch
  (fn [{:keys [onyx.core/id onyx.core/compressed onyx.core/lifecycle-id]}]
    (taoensso.timbre/info (format "[%s / %s] Compressed %s segments" id lifecycle-id (count compressed)))))

(dire/with-post-hook! #'munge-write-batch
  (fn [{:keys [onyx.core/id onyx.core/lifecycle-id]}]
    (taoensso.timbre/info (format "[%s / %s] Wrote batch" id lifecycle-id))))

(dire/with-post-hook! #'munge-status-check
  (fn [{:keys [onyx.core/id onyx.core/status-node onyx.core/lifecycle-id]}]
    (taoensso.timbre/info (format "[%s / %s] Checked the status node" id lifecycle-id))))

(dire/with-post-hook! #'munge-ack
  (fn [{:keys [onyx.core/id onyx.core/acked onyx.core/lifecycle-id]}]
    (taoensso.timbre/info (format "[%s / %s] Acked %s segments" id lifecycle-id acked))))

(dire/with-post-hook! #'munge-commit-tx
  (fn [{:keys [onyx.core/id onyx.core/commit? onyx.core/lifecycle-id]}]
    (taoensso.timbre/info (format "[%s / %s] Committed transaction? %s" id lifecycle-id commit?))))

(dire/with-post-hook! #'munge-close-resources
  (fn [{:keys [onyx.core/id onyx.core/lifecycle-id]}]
    (taoensso.timbre/info (format "[%s / %s] Closed resources" id lifecycle-id))))

(dire/with-post-hook! #'munge-close-temporal-resources
  (fn [{:keys [onyx.core/id onyx.core/lifecycle-id]}]
    (taoensso.timbre/info (format "[%s / %s] Closed temporal plugin resources" id lifecycle-id))))

(dire/with-post-hook! #'munge-seal-resource
  (fn [{:keys [onyx.core/id onyx.core/task onyx.core/sealed? onyx.core/lifecycle-id]}]
    (taoensso.timbre/info (format "[%s / %s] Sealing resource for %s? %s" id lifecycle-id task sealed?))))

(dire/with-post-hook! #'munge-complete-task
  (fn [{:keys [onyx.core/id onyx.core/lifecycle-id]}]
    (taoensso.timbre/info (format "[%s / %s] Completing task" id lifecycle-id))))

