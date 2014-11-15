(ns ccm-clj
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [ccm-clj.impl :refer :all]
            [ccm-clj.impl :as impl])
  (:import [java.net URL]
           [java.io File]))

;;;;;;;;;;;;;
;;; Public

(defn exec!
  "Execute ccm `cmd`, returns {:err <stderr messages>, :out <stdout messages>, :exit <return code>}"
  [cmd & args]
  (log/info "??")
  (apply ccm cmd args))

(defn active-cluster
  "Get name of active cluster"
  []
  (get-active))

(defn cluster?
  "Is `name` found in list of CCM clusters."
  [name]
  (not (nil? (re-matches (re-pattern (str "(?s)" ".*?\\b" name "\\b.*?")) (:out (ccm "list" :quiet))))))

(defn set-default-keyspace!
  "Set default keyspace for `cluster` or the active cluster, persists across cluster switches, clears on remove."
  ([keyspace]
   (if (ensure-active)
     (set-default-keyspace! (active-cluster) keyspace)))
  ([cluster keyspace]
   (if (cluster? cluster)
     (swap! default-keyspaces assoc cluster keyspace)
     (log/error "No cluster " cluster " to set default keyspace on"))))

(defn default-keyspace
  "Get name of default keyspace of `cluster` or active cluster"
  ([]
   (if (ensure-active)
     (@default-keyspaces (active-cluster))))
  ([cluster]
   (@default-keyspaces cluster)))

(defn cluster-conf
  "Get a map of the cluster conf"
  ([]
   (if (ensure-active)
     (cluster-conf (active-cluster))))
  ([name]
   (conf-as-map (io/file ccm-dir name "cluster.conf"))))

(defn node-conf
  "Get a map of the conf for the node `name` from active cluster"
  ([name]
   {:pre (= (active-cluster) nil)}
   (conf-as-map (io/file ccm-dir (active-cluster) name (str name ".conf")))))

(defn switch!
  "Switch active cluster to `name`"
  [name]
  (let [result (ccm "switch" name)]
    (log/info (str "Switched active cluster to " name))
    result))

(defn start!
  "Start CCM cluster `name`."
  ([]
   (if (ensure-active)
     (start! (active-cluster))))
  ([name]
   (let [result (ccm "start")]
     (log/info (str "Cluster " name " started"))
     (switch! name)
     result)))

(defn cql!
  "Execute cqlsh cmd (against 'node1') in keyspace `keyspace` from cmd-source (can be File, String or URL) into active cluster.
  Note this is a convienent way of loading schemas and seed data, and shouldn't be used in place of a proper CQL client like Alia or Cassaforte."
  ([cmd-source]
   (cql! cmd-source (@default-keyspaces (active-cluster)) "node1"))
  ([cmd-source keyspace]
   (cql! cmd-source keyspace "node1"))
  ([cmd-source keyspace node-name]
   (log/info (str "Loading cql: " (to-str cmd-source)))
   (let [arg (as-cqlsh-arg cmd-source)
         result (apply ccm (concat (if keyspace [node-name "cqlsh" "-k" keyspace] [node-name "cqlsh"]) arg))]
     result)))

(defn clusters
  "Returns a vector of clusters in CCM"
  []
  (vec (re-seq #"[^\n\s*]+" (:out (ccm "list" :quiet)))))

(defn stop!
  "Stop the current CCM cluster."
  []
  (if (ensure-active)
    (let [result (ccm "stop")]
      (log/info (str (active-cluster) " cluster stopped"))
      result)
    (log/info "No active cluster to stop")))

(defn flush!
  "If `node-name` flush that node on active cluster, else flush all nodes"
  ([]
   (let [result (ccm "flush")]
     (log/info (str (active-cluster) " node flushed"))
     result))
  ([node-name]
   (let [result (ccm "flush")]
     (log/info (str node-name " node flushed"))
     result)))

(defn remove!
  "Remove cluster `name` from CCM including ALL DATA, resetting active cluster if `name`.
  If `ccm remove` fails will atttempt to delete .ccm/`name`"
  [name]
  (let [result (ccm "remove" name)
        cluster-dir (io/file ccm-dir name)]
    (if (.exists cluster-dir) (del-dir cluster-dir))
    (swap! default-keyspaces dissoc name)
    (log/info (str "Cluster " name " removed"))
    result))

(defn add-node!
  "Add node `node-name` at next available loopback to active cluster."
  ([node-name ip ports-spec]
   (if (and (ensure-active) (not (or ports-spec (:cql ports-spec))))
     (throw (IllegalArgumentException. "Ports spec must be  a cql port OR map containing :cql port")))

   (let [ports-spec (if-not (map? ports-spec) {:cql ports-spec} ports-spec)
         cql (:cql ports-spec)
         {:keys [storage thrift jmx]
          :or   {storage (+ 1 cql)
                 thrift  (+ 2 cql)
                 jmx     (+ @jmx-increment cql)}} ports-spec]
     (swap! jmx-increment + 10)
     (ccm "add"
          "-r" "0"
          "-j" (str jmx)                                    ;NO IP!
          "-l" (str ip ":" storage)
          "-t" (str ip ":" thrift)
          (str "--binary-itf=" (str ip ":" cql))
          node-name)
     (log/info "Added node" node-name "@" (str ip ":" cql)))))

(defn remove-node! [node-name]
  "Remove node `node-name` from active cluster."
  (if (ensure-active)
    (ccm node-name "remove")))

(defn new!
  "Create and start cluster.
  `num-nodes` Cassandra nodes will be running using ports inferred from ports-spec
  Those loopbacks may need to be aliased depending on OS.
  A an optional map `ports-spec` can be used to supply :cql and any of :storage :thift :jmx port,
  else ports from cql-port to cql-port + 3 will be used."
  ([name version num-nodes]
   (new! name version num-nodes {}))
  ([name version num-nodes ports-spec]
   (let [ports-spec (merge
                      {:cql default-base-port}
                      (if (map? ports-spec) ports-spec {:cql ports-spec}))
         cluster-dir (io/file ccm-dir name)]
     (if (not (cluster? name))
       (do
         (log/info "Creating new cluster" name "listening on" (:cql ports-spec))
         (ccm "create" name "-v" (str version))
         (doseq [i (range 1 (inc num-nodes))
                 :let [ip (str "127.0.0." i)
                       node-name (str "node" i)
                       cql (:cql ports-spec)]]
           (add-node! node-name ip {:cql cql})))
       (log/info "Found existing cluster at" cluster-dir))
     (start! name))))

(defn savepoint?
  "Is `name` a savepoint of active cluster or `cluster`."
  ([name]
   (if (ensure-active)
     (savepoint? (active-cluster) name)))
  ([cluster name]
   (.exists (io/file savepoint-dir cluster name))))

(defn savepoint!
  "Create savepoint `name` from `cluster` or active cluster that can be reset to via `rollback!`."
  ([name]
   (if (ensure-active)
     (savepoint! (active-cluster) name)
     false))
  ([cluster name]
   (let [cluster-savepoint-dir (io/file savepoint-dir cluster)]
     (if-not (.exists cluster-savepoint-dir)
       (.mkdirs cluster-savepoint-dir))
     (let [save-dir (io/file cluster-savepoint-dir name)
           cluster-dir (io/file ccm-dir cluster)]
       (flush!)
       (if (copy-files cluster-dir save-dir)
         (do (log/info "Created savepoint" name "in cluster" cluster)
             true)
         (do (log/error "Failed to create savepoint" name "in cluster " cluster)
             (del-dir save-dir)
             false))))))

(defn restore!
  "Rollback active cluster or `cluster` to savepoint `name` and start the cluster"
  ([savepoint]
   (if (ensure-active)
     (restore! (active-cluster) savepoint)))
  ([cluster savepoint]
   (let [save-dir (io/file savepoint-dir cluster savepoint)
         cluster-dir (io/file ccm-dir cluster)]
     (if (active-cluster) (stop!))
     (if (= (:exit (sync-dir save-dir cluster-dir) 0))
       (do (log/info "Restored to savepoint" savepoint)
           (start! cluster)
           true)
       (do (log/error "Failed to rollback to savepoint" savepoint)
           false)))))

(defn remove-savepoint!
  "Remove `savepoint` from active cluster or `cluster` if supplied"
  ([savepoint]
   (if (ensure-active)
     (remove-savepoint! (active-cluster) savepoint)))
  ([cluster savepoint]
   (let [save-dir (io/file savepoint-dir cluster savepoint)]
     (if (del-dir save-dir)
       (do (log/info "Deleted savepoint" savepoint "in cluster" cluster)
           true)
       (do (log/error "Failed to delete savepoint" savepoint "in cluster" cluster)
           false)))))

(defn remove-savepoints!
  "Remove all savepoints from active cluster or `cluster` if supplied"
  ([]
   (if (ensure-active)
     (remove-savepoints! (active-cluster))))
  ([cluster]
   (let [save-dir (io/file savepoint-dir cluster)]
     (if (del-dir save-dir)
       (do (log/info "Deleted savepoints for" cluster)
           true)
       (do (log/error "Failed to deleted savepoints for" cluster)
           false)))))

(defn auto-cluster!
  "Experimental :

  Will either reuse cluster `name` if it has a snapshot created by previous invocation of auto-cluster!,
  or will create new cluster as per new! and start! and loading of cql files from the classpath resources
  as per `keyspace->clq-re`, a map of keyspame to regular expression(s) matching the files to load against
  that keyspace from those on the classpath. The files will be loaded in name order generally but with priority
  given to files containing the word \"keyspace\".

  For example:
   {\"testkeyspace\": #\"schema/.*.cql\"}  would match the contents of the path ./schema
    my-keyspace.cql, 1_schema.cql, 2_data.cql, 3_data.cql
    and they would be loaded in that order.

    {\"testkeyspace\": [#\"keyspace/.*.cql\", #\"schema/.*.cql\", #\"data/.*.cql\"]} would have the same effect."

  ([name version num-nodes keyspace->cql-re]
   (auto-cluster! name version num-nodes keyspace->cql-re {}))
  ([name version num-nodes keyspace->cql-re opts]
   (if (not (and (savepoint? name "_initial") (restore! name "_initial")))
     (try
       (if (cluster? name) (remove! name))
       (new! name version num-nodes opts)
       (doseq [keyspace (keys keyspace->cql-re)
               cql-res (keyspace->cql-re keyspace)
               :let [cql-res (if (sequential? cql-res) cql-res [cql-res])]]
         (let [cqls (mapcat classpath-resources cql-res)
               _ (if (empty? cqls) (log/warn "No sources matching" cql-res "found on classpath") (log/info "Found cql:" cqls))
               {keyspace-cql true data-cql false} (group-by #(some? (re-matches #"(?i).*keyspace[^/]*.cql" (.getFile %))) cqls)]
           (doseq [k (sort-by #(.getFile %) keyspace-cql)]
             (log/error k)
             (cql! k))
           (doseq [d (sort-by #(.getFile %) data-cql)]
             (log/error d)
             (cql! d keyspace))))
       (do (savepoint! "_initial"))
       (catch Throwable t (do (log/error "Error autostarting cluster " name)
                              (if (active-cluster) (stop!))
                              (throw t)))))))