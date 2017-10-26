(ns jepsen.etcdemo
  (:gen-class)
  (:require [clojure.string :as str]
            [clojure.tools.logging :refer :all]
            [jepsen [checker :as checker]
                    [cli :as cli]
                    [client :as client]
                    [control :as c]
                    [db :as db]
                    [generator :as gen]
                    [nemesis :as nemesis]
                    [tests :as tests]
                    [util :as util :refer [timeout]]]
            [jepsen.checker.timeline :as timeline]
            [jepsen.control.util :as cu]
            [jepsen.os.debian :as debian]
            [knossos.model :as model]
            [slingshot.slingshot :refer [try+]]
            [verschlimmbesserung.core :as v]))

(def dir "/opt/etcd")
(def binary "etcd")
(def logfile (str dir "/etcd.log"))
(def pidfile (str dir "/etcd.pid"))

(defn node-url [node port]
  (str "http://" (name node) ":" port))

(defn peer-url [node]
  (node-url node 2380))

(defn peer [node]
  (str (name node) "=" (peer-url node)))

(defn initial-cluster [{:keys [nodes]}]
  "Takes a test map as argument"
  (->> nodes
       (map peer)
       (str/join ",")))

(defn client-url [node]
  (node-url node 2379))

(deftype Etcd [version]
  db/DB
  (setup! [db test node]
    (info "Setting up etcd")
    (c/su
      (let [url (str "https://storage.googleapis.com/etcd/"
                     version
                     "/etcd-"
                     version
                     "-linux-amd64.tar.gz")]
        (cu/install-archive! url dir))

      (cu/start-daemon!
        {:logfile logfile
         :pidfile pidfile
         :chdir   dir}
        binary
        :--log-output                  :stderr
        :--name                        (name node)
        :--listen-peer-urls            (peer-url node)
        :--listen-client-urls          (client-url node)
        :--advertise-client-urls       (client-url node)
        :--initial-cluster-state       :new
        :--initial-advertise-peer-urls (peer-url node)
        :--initial-cluster             (initial-cluster test))

      (Thread/sleep 5000)))

  (teardown! [db test node]
    (info "Tearing down etcd")
    (cu/stop-daemon! binary pidfile)
    (c/su
      (c/exec :rm :-rf dir)))

  db/LogFiles
  (log-files [db test node]
    [logfile]))

(defn r   [_ _] {:type :invoke, :f :read,  :value nil})
(defn w   [_ _] {:type :invoke, :f :write, :value (rand-int 5)})
(defn cas [_ _] {:type :invoke, :f :cas,   :value [(rand-int 5) (rand-int 5)]})

(defn parse-long [s]
  (when s (Long/parseLong s)))

(defrecord EtcdClient [conn]
  client/Client
  (setup! [client test node]
    (assoc client
           :conn (v/connect (client-url node)
                            {:timeout 5000})))

  (invoke! [client test op]
    (case (:f op)
      :read (assoc op
                   :type :ok,
                   :value (parse-long (v/get conn "cats")))
      :write (do (v/reset! conn "cats" (:value op))
                 (assoc op :type :ok))
      :cas (try+
             (let [[v v'] (:value op)]
               (assoc op :type (if (v/cas! conn "cats" v v')
                                 :ok
                                 :fail)))
             (catch [:errorCode 100] _
               (assoc op :type :fail, :error :not-found)))))

  (teardown! [_ test]))

(defn etcd-client [] (->EtcdClient nil))

(defn generator [time-limit]
  (->> (gen/mix [r w cas])
       (gen/stagger 1/10)
       (gen/nemesis
         (gen/seq (cycle [(gen/sleep 5)
                          {:type :info, :f :start}
                          (gen/sleep 5)
                          {:type :info, :f :stop}])))
       (gen/time-limit time-limit)))

(defn etcd-test
  [opts]
  (merge tests/noop-test
         opts
         {:name      "etcdemo"
          :os        debian/os
          :db        (->Etcd "v3.1.5")
          :client    (etcd-client)
          :nemesis   (nemesis/partition-random-halves)
          :generator (generator (:time-limit opts))
          :model     (model/cas-register)
          :checker   (checker/compose
                       {:linear (checker/linearizable)
                        :timeline (timeline/html)
                        :perf (checker/perf)})}))

(defn -main [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn etcd-test})
                   (cli/serve-cmd))
            args))

;; temp stuff below

(defn db [version]
  (reify db/DB
    (setup! [db test node]
      (info "Setting up etcd"))

    (teardown! [db test node]
      (info "Tearing down etcd"))))

(defn client [conn]
  (reify client/Client
    (setup! [_ test node]
      (client (v/connect (client-url node)
                         {:timeout 5000})))
  (invoke! [_ test op]
    (case (:f op)
      :read (assoc op
                   :type :ok,
                   :value (parse-long (v/get conn "cats")))))

  (teardown! [_ test])))

