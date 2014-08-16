(ns ccm-clj-test
  (:require [clojure.java.io :as io]
            [expectations :refer :all]
            [ccm-clj :refer :all]))

(def existing (get-clusters))
(def current-cluster (get-active-cluster))
(def current-keyspace (get-default-keyspace))

(defn tidy-up {:expectations-options :after-run} []
  (if (cluster? "ccmcljtest1") (ccm-clj/remove! "ccmcljtest1"))
  (if (cluster? "ccmcljtest2") (ccm-clj/remove! "ccmcljtest2"))
  (set-default-keyspace! current-keyspace)
  (if (and current-cluster (cluster? current-cluster)) (switch! current-cluster)))

(defmacro expect-no-stderr [body]
  `(expect "" (:err (~@body))))

(expect-no-stderr (new! "ccmcljtest1" "2.0.4" 3 20111))

;cql as file
(expect-no-stderr (cql! (io/file "./test/resources/test-keyspace.cql")))
;cql as url, keyspace in file
(expect-no-stderr (cql! (io/resource "test-schema.cql")))
;file with given keyspace
(expect-no-stderr (cql! (io/file "./test/resources/test-data.cql") "ccmclj"))

(expect (set-default-keyspace! "ccmclj"))
;load string using default keyspace - and trim surplus ; which ccm spews on   ;todo still doesnt work?
;(expect (cql! "update testtable set data = '22' where id = 2; insert into testtable (id, data) values (3, '2')"));
;(expect (slurp (io/file "./test/resources/test-data2.table")) (cql! "select * from testtable"))

(expect (set ["node1" "node2" "node3"]) (set (:nodes (get-cluster-conf))))

(expect "ccmcljtest1" (get-active-cluster))
(expect (conj (set existing) "ccmcljtest1") (set (get-clusters)))
(expect (cluster? "ccmcljtest1"))
(expect "ccmclj" (get-default-keyspace))

(expect (not (cluster? "ccmcljtest2")))
(expect (new! "ccmcljtest2" "2.0.4" 2 20211))
(expect "ccmcljtest2" (get-active-cluster))
(expect (set ["node1" "node2"]) (set (:nodes (get-cluster-conf))))
(expect (remove! "ccmcljtest2"))
(expect nil (get-active-cluster))

(expect (switch! "ccmcljtest1"))
(expect (stop!))
(expect (start! "ccmcljtest1"))
(expect (switch! "ccmcljtest1"))
(expect "ccmcljtest1" (get-active-cluster))


(expect (hash-set "node1" "node2" "node3" "node4")
        (do (add-node! "node4" "127.0.0.4" 20111)
            (set (:nodes (get-cluster-conf)))))

(expect (remove-node! "node4"))
(expect (hash-set "node1" "node2" "node3") (set (:nodes (get-cluster-conf))))

