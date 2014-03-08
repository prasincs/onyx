(ns onyx.peer.hornetq-util
  (:import [org.hornetq.api.core.client HornetQClient]
           [org.hornetq.api.core TransportConfiguration HornetQQueueExistsException]
           [org.hornetq.core.remoting.impl.netty NettyConnectorFactory]))

(defn create-queue [session queue-name]
  (try
    (.createQueue session queue-name queue-name true)
    (catch Exception e)))

(defn write-and-cap! [queue-name messages]
  (let [tc (TransportConfiguration. (.getName NettyConnectorFactory))
        locator (HornetQClient/createServerLocatorWithoutHA (into-array [tc]))
        session-factory (.createSessionFactory locator)
        session (.createTransactedSession session-factory)]
    
    (create-queue session queue-name)
    
    (let [producer (.createProducer session queue-name)]
      (.start session)
      (doseq [m messages]
        (let [message (.createMessage session true)]
          (.writeString (.getBodyBuffer message) m)
          (.send producer message)))

      (let [sentinel (.createMessage session true)]
        (.writeString (.getBodyBuffer sentinel) (pr-str :done))
        (.send producer sentinel))

      (.commit session)
      (.close producer)
      (.close session))))

(defn read! [queue-name n]
  (let [tc (TransportConfiguration. (.getName NettyConnectorFactory))
        locator (HornetQClient/createServerLocatorWithoutHA (into-array [tc]))
        session-factory (.createSessionFactory locator)
        session (.createTransactedSession session-factory)]

    (create-queue session queue-name)
    
    (let [consumer (.createConsumer session queue-name "" 0 64000 false)
          results (atom [])]
      (.start session)
      (doseq [k (range n)]
        (let [message (.receive consumer)]
          (.acknowledge message)
          (swap! results conj (read-string (.readString (.getBodyBuffer message))))))
      (.commit session)
      (.close consumer)
      (.close session)

      @results)))
