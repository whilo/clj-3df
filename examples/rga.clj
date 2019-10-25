(ns rga
  (:require
   [clojure.pprint :as pprint]
   [clj-3df.core :as d]
   [clj-3df.attribute :as attribute]))

;; RGA
;; Datalog version of an ordered list CRDT, translated from Martin
;; Kleppman's work:
;;
;; https://speakerdeck.com/ept/data-structures-as-queries-expressing-crdts-using-datalog?slide=22

(def schema
  {:insert/after (merge
                  (attribute/of-type :String)
                  (attribute/input-semantics :db.semantics/raw)
                  (attribute/tx-time))
   :assign/elem  (merge
                  (attribute/of-type :String)
                  (attribute/input-semantics :db.semantics/raw)
                  (attribute/tx-time))
   :assign/value (merge
                  (attribute/of-type :String)
                  (attribute/input-semantics :db.semantics/raw)
                  (attribute/tx-time))
   :remove?      (merge
                  (attribute/of-type :Bool)
                  (attribute/input-semantics :db.semantics/raw)
                  (attribute/tx-time))})

(def db (d/create-db schema))


(def rules
  '[[(has-child? ?parent) [?wc :insert/after ?parent]]

    [(later-child ?parent ?child2)
     [?child1 :insert/after ?parent]
     [?child2 :insert/after ?parent]
     [(> ?child1 ?child2)]]

    [(first-child ?parent ?child)
     [?child :insert/after ?parent]
     (not (later-child ?parent ?child))]

    [(sibling? ?child1 ?child2)
     [?child1 :insert/after ?parent]
     [?child2 :insert/after ?parent]]

    [(later-sibling ?sib1 ?sib2)
     (sibling? ?sib1 ?sib2)
     [(> ?sib1 ?sib2)]]

    [(later-sibling2 ?sib1 ?sib3)
     (sibling? ?sib1 ?sib2)
     (sibling? ?sib1 ?sib3)
     [(> ?sib1 ?sib2)]
     [(> ?sib2 ?sib3)]]

    [(next-sibling ?sib1 ?sib2)
     (later-sibling ?sib1 ?sib2)
     (not (later-sibling2 ?sib1 ?sib2))]

    [(has-next-sibling? ?sib1) (later-sibling ?sib1 ?wc)]

    [(next-sibling-anc ?start ?next) (next-sibling ?start ?next)]
    [(next-sibling-anc ?start ?next)
     [?start :insert/after ?parent]
     (next-sibling-anc ?parent ?next)
     (not (has-next-sibling? ?start))]

    [(next-elem ?prev ?next) (first-child ?prev ?next)]
    [(next-elem ?prev ?next)
     (next-sibling-anc ?prev ?next)
     (not (has-child? ?prev))]

    ;; Assigning values to list elements.

    [(current-value ?elem ?value)
     [?op :assign/elem ?elem]
     [?op :assign/value ?value]
     (not [?op :remove? true])]

    [(has-value? ?elem) (current-value ?elem ?wc)]

    [(skip-blank ?from ?to) (next-elem ?from ?to)]
    [(skip-blank ?from ?to)
     (next-elem ?from ?via)
     (not (has-value? ?via))
     (skip-blank ?via ?to)]

    [(next-visible ?prev ?next)
     (has-value? ?prev)
     (skip-blank ?prev ?next)
     (has-value? ?next)]

    ;; Output

    [(result ?id1 ?id2 ?value)
     ;; (-id ?id1 ?ctr1 ?wc)
     (next-visible ?id1 ?id2)
     (current-value ?id2 ?value)]
    ])

;; query

(def q '[:find ?id1 ?id2 ?value :where (result ?id1 ?id2 ?value)])
;; (def q1 '[:find ?id ?ctr ?node :where (-id ?id ?ctr ?node)])
;; (def q2 '[:find ?id ?value :where (current-value ?id ?value)])
;; (def q3 '[:find ?id1 ?id2 :where (next-visible ?id1 ?id2)])
;; (def q4 '[:find ?id1 ?id2 :where (next-elem ?id1 ?id2)])
;; (def q5 '[:find ?id :where (has-value? ?id)])


;; test

(defn rga->list [rga]
  (let [ks (keys rga) 
        reverse-lookup (into {} (map (fn [[parent pos]] [parent [parent pos]]) ks))
        fst (first (sort ks))]
    (loop [s [(rga fst)]
           [parent pos] fst]
      (if-let [next (reverse-lookup pos)]
        (recur (conj s (rga next))
               next)
        s))))

(comment
  (def server-path "/home/christian/Development/declarative-dataflow/server/target/debug/declarative-server") 

  ;; TODO list
  ;; 1. Verify result "Hey?" under different (valid) permutations of ops
  ;; 2. Port Martin's large benchmark from Souffle to setup below.
  ;; 3. Compare Martin's large benchmark result (104,851) to datahike/datascript
  (let [server (.start (ProcessBuilder. (into-array String [server-path])))]
    (try
      (Thread/sleep 500)
      (def op-codes {-2 :remove
                     -1 :remove
                     1  :add
                     2  :add}) 

      (def conn (d/create-debug-conn! "ws://127.0.0.1:6262")) 
      (d/exec! conn (d/create-db-inputs db)) 
      (def rga-id (subs (str "rga-" (java.util.UUID/randomUUID)) 0 8)) 
      (d/exec! conn (d/query db rga-id q rules))
      (def rga-view (atom {}))
      (d/listen-query! conn rga-id
                       (fn [diffs]
                         (swap! rga-view
                                (fn [old]
                                  (println "diffs: " diffs)
                                  (reduce
                                   (fn [old [[after pos char] t op]]
                                     (let [[pos _] (read-string (:String pos))
                                           [parent _] (read-string (:String after))]
                                       ((case (op-codes op)
                                          :add assoc
                                          :remove dissoc)
                                        old
                                        [parent pos]
                                        (:String char))))
                                   old
                                   diffs))))) 
      (d/exec! conn
               (d/transact db [[:db/add "[1 0]" :insert/after "[0 0]"]
                               [:db/add "[2 0]" :insert/after "[0 0]"]
                               [:db/add "[3 0]" :insert/after "[2 0]"]
                               [:db/add "[4 0]" :insert/after "[1 0]"]
                               [:db/add "[5 0]" :insert/after "[2 0]"]
                               [:db/add "[6 0]" :insert/after "[2 0]"]])
               (d/transact db [{:db/id "[0 1]" :assign/elem "[0 0]" :assign/value "-"}
                               {:db/id "[2 1]" :assign/elem "[2 0]" :assign/value "H"}
                               {:db/id "[6 1]" :assign/elem "[6 0]" :assign/value "e"}
                               {:db/id "[6 2]" :assign/elem "[6 0]" :assign/value "i"}
                               {:db/id "[5 1]" :assign/elem "[5 0]" :assign/value "l"}
                               [:db/add "[6 2]" :remove? true]
                               ]))
      (Thread/sleep 500)
      (d/exec! conn (d/transact db [
                                    ;; moving here breaks: [:db/add "[6 2]" :remove? true]
                                    [:db/add "[5 1]" :remove? true]
                                    {:db/id "[5 3]" :assign/elem "[5 0]" :assign/value "y"}
                                    {:db/id "[3 1]" :assign/elem "[3 0]" :assign/value "l"}
                                    [:db/add "[3 1]" :remove? true]
                                    {:db/id "[1 1]" :assign/elem "[1 0]" :assign/value "o"}
                                    {:db/id "[1 2]" :assign/elem "[1 0]" :assign/value "i"}
                                    {:db/id "[4 1]" :assign/elem "[4 0]" :assign/value "!"}
                                    {:db/id "[4 2]" :assign/elem "[4 0]" :assign/value "?"}
                                    [:db/add "[1 1]" :remove? true]
                                    [:db/add "[1 2]" :remove? true]]))
      (Thread/sleep 1000)
      (println @rga-view #_(rga->list @rga-view))
      (catch Exception e
        (throw e))
      (finally
        (.close (:ws conn))
        (.destroy server)))))

(defn -main []
  (def conn
    (d/create-debug-conn! "ws://127.0.0.1:6262")) 
  
  (d/exec! conn
    (d/create-db-inputs db)) 
  
  (d/exec! conn
    (d/query db "RGA" q rules)
    ;; (query db "q_-id" q1 rules)
    ;; (query db "q_current-value" q2 rules)
    ;; (query db "q_next-visible" q3 rules)
    ;; (query db "q_next-elem" q4 rules)
    ;; (query db "q_has-value" q5 rules)
    )


  ;; figure out order
  (d/exec! conn
    (d/transact db [[:db/add "[1 0]" :insert/after "[0 0]"]
                    [:db/add "[2 0]" :insert/after "[0 0]"]
                    [:db/add "[3 0]" :insert/after "[2 0]"]
                    [:db/add "[4 0]" :insert/after "[1 0]"]
                    [:db/add "[5 0]" :insert/after "[2 0]"]
                    [:db/add "[6 0]" :insert/after "[2 0]"]])
    (d/transact db [{:db/id "[0 1]" :assign/elem "[0 0]" :assign/value "-"}
                    {:db/id "[2 1]" :assign/elem "[2 0]" :assign/value "H"}
                    {:db/id "[6 1]" :assign/elem "[6 0]" :assign/value "e"}
                    {:db/id "[6 2]" :assign/elem "[6 0]" :assign/value "i"}
                    [:db/add "[6 2]" :remove? true]
                    {:db/id "[5 1]" :assign/elem "[5 0]" :assign/value "l"}
                    [:db/add "[5 1]" :remove? true]
                    {:db/id "[5 3]" :assign/elem "[5 0]" :assign/value "y"}
                    {:db/id "[3 1]" :assign/elem "[3 0]" :assign/value "l"}
                    [:db/add "[3 1]" :remove? true]
                    {:db/id "[1 1]" :assign/elem "[1 0]" :assign/value "o"}
                    {:db/id "[1 2]" :assign/elem "[1 0]" :assign/value "i"}
                    [:db/add "[1 1]" :remove? true]
                    [:db/add "[1 2]" :remove? true]
                    {:db/id "[4 1]" :assign/elem "[4 0]" :assign/value "!"}
                    {:db/id "[4 2]" :assign/elem "[4 0]" :assign/value "?"}]))
  )
