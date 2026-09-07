(ns bwr.store.node
  "XTDB 1.x node lifecycle management and topology configurations.
   Supports in-memory (testing/ephemeral) and RocksDB (persistent production) topologies."
  (:require [xtdb.api :as xt]
            [clojure.java.io :as io]))

(set! *warn-on-reflection* true)

(defn in-memory-config
  "Returns XTDB topology configuration for an in-memory node."
  []
  {})

(defn rocksdb-config
  "Returns XTDB topology configuration with RocksDB persistence for index-store,
   document-store, and tx-log."
  [data-dir]
  (let [root (io/file data-dir)
        index-dir (io/file root "indices")
        doc-dir (io/file root "docs")
        tx-dir (io/file root "tx-log")]
    {:xtdb/index-store
     {:kv-store {:xtdb/module 'xtdb.rocksdb/->kv-store
                 :db-dir index-dir}}
     :xtdb/document-store
     {:kv-store {:xtdb/module 'xtdb.rocksdb/->kv-store
                 :db-dir doc-dir}}
     :xtdb/tx-log
     {:kv-store {:xtdb/module 'xtdb.rocksdb/->kv-store
                 :db-dir tx-dir}}}))

(defn start-node!
  "Starts an XTDB node with the given configuration options.
   Opts map can include:
     :topology - :in-memory (default) or :rocksdb
     :data-dir - path string if :topology is :rocksdb (default 'data/xtdb')"
  ([] (start-node! {:topology :in-memory}))
  ([opts]
   (let [topology (get opts :topology :in-memory)
         config (case topology
                  :in-memory (in-memory-config)
                  :rocksdb (rocksdb-config (get opts :data-dir "data/xtdb"))
                  (throw (ex-info "Unknown XTDB topology" {:topology topology})))]
     (xt/start-node config))))

(defn stop-node!
  "Stops and closes an XTDB node cleanly."
  [node]
  (when node
    (.close ^java.io.Closeable node)))
