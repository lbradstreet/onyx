(ns onyx.log.generative-peer-join
  (:require [onyx.messaging.dummy-messenger :refer [->DummyMessenger]]
            [onyx.log.generators :as log-gen]
            [onyx.extensions :as extensions]
            [onyx.api :as api]
            [onyx.static.planning :as planning]
            [onyx.test-helper :refer [job-allocation-counts]]
            [clojure.set :refer [intersection]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test :refer :all]
            [com.gfredericks.test.chuck.clojure-test :refer [checking]]))

(def onyx-id (java.util.UUID/randomUUID))

(def peer-config 
  {:onyx/id onyx-id
   :onyx.messaging/impl :dummy-messenger})

(def messenger (->DummyMessenger))

(def job-1-id #uuid "f55c14f0-a847-42eb-81bb-0c0390a88608")

(def job-1
  {:workflow [[:a :b] [:b :c]]
   :catalog [{:onyx/name :a
              :onyx/ident :core.async/read-from-chan
              :onyx/type :input
              :onyx/medium :core.async
              :onyx/batch-size 20
              :onyx/doc "Reads segments from a core.async channel"}

             {:onyx/name :b
              :onyx/fn :mock/fn
              :onyx/type :function
              :onyx/batch-size 20}

             {:onyx/name :c
              :onyx/ident :core.async/write-to-chan
              :onyx/type :output
              :onyx/medium :core.async
              :onyx/batch-size 20
              :onyx/doc "Writes segments to a core.async channel"}]
   :task-scheduler :onyx.task-scheduler/balanced})

(def job-2-id #uuid "5813d2ec-c486-4428-833d-e8373910ae14")

(def job-2
  {:workflow [[:d :e] [:e :f]]
   :catalog [{:onyx/name :d
              :onyx/ident :core.async/read-from-chan
              :onyx/type :input
              :onyx/medium :core.async
              :onyx/batch-size 20
              :onyx/doc "Reads segments from a core.async channel"}

             {:onyx/name :e
              :onyx/fn :mock/fn
              :onyx/type :function
              :onyx/batch-size 20}

             {:onyx/name :f
              :onyx/ident :core.async/write-to-chan
              :onyx/type :output
              :onyx/medium :core.async
              :onyx/batch-size 20
              :onyx/doc "Writes segments to a core.async channel"}]
   :task-scheduler :onyx.task-scheduler/balanced})

(def job-3-id #uuid "58d199e8-4ea4-4afd-a112-945e97235924")

(def job-3
  {:workflow [[:g :h] [:h :i]]
   :catalog [{:onyx/name :g
              :onyx/ident :core.async/read-from-chan
              :onyx/type :input
              :onyx/medium :core.async
              :onyx/batch-size 20
              :onyx/doc "Reads segments from a core.async channel"}

             {:onyx/name :h
              :onyx/fn :mock/fn
              :onyx/type :function
              :onyx/batch-size 20}

             {:onyx/name :i
              :onyx/ident :core.async/write-to-chan
              :onyx/type :output
              :onyx/medium :core.async
              :onyx/batch-size 20
              :onyx/doc "Writes segments to a core.async channel"}]
   :task-scheduler :onyx.task-scheduler/balanced})

(defn generate-join-entries [peer-ids]
  (zipmap peer-ids 
          (map (fn [id] [{:fn :prepare-join-cluster 
                         :immediate? true
                         :args {:peer-site (extensions/peer-site messenger)
                                :joiner id}}])
               peer-ids)))

(defn generate-peer-ids [n]
  (map #(keyword (str "p" %))
       (range 1 (inc n))))

(deftest greedy-allocation
  (checking
   "Checking greedy allocation causes all peers to be allocated to one of two jobs"
   1000
   [{:keys [replica log peer-choices]} 
    (log-gen/apply-entries-gen 
     (gen/return
      {:replica {:job-scheduler :onyx.job-scheduler/greedy
                 :messaging {:onyx.messaging/impl :dummy-messenger}}
       :message-id 0
       :entries (assoc
                    (generate-join-entries (generate-peer-ids 8))
                  :job-1 [(api/create-submit-job-entry job-1-id
                                                       peer-config 
                                                       job-1 
                                                       (planning/discover-tasks (:catalog job-1) (:workflow job-1)))]
                  :job-2 [(api/create-submit-job-entry job-2-id
                                                       peer-config 
                                                       job-2 
                                                       (planning/discover-tasks (:catalog job-2) (:workflow job-2)))])
       :log []
       :peer-choices []}))]

   (let [allocs (vector (apply + (map count (vals (get (:allocations replica) job-1-id))))
                        (apply + (map count (vals (get (:allocations replica) job-2-id)))))]
     (is 
      (or (= allocs [0 8])
          (= allocs [8 0]))))))

(deftest greedy-allocation-reallocated
  (checking
   "Checking peers reallocated to other job when killed"
   1000
   [{:keys [replica log peer-choices]} 
    (log-gen/apply-entries-gen 
     (gen/return
      {:replica {:job-scheduler :onyx.job-scheduler/greedy
                 :messaging {:onyx.messaging/impl :dummy-messenger}}
       :message-id 0
       :entries (assoc (generate-join-entries (generate-peer-ids 8))
                  :job-1 [(api/create-submit-job-entry job-1-id
                                                       peer-config 
                                                       job-1 
                                                       (planning/discover-tasks (:catalog job-1) (:workflow job-1)))]
                  :job-2 [(api/create-submit-job-entry job-2-id
                                                       peer-config 
                                                       job-2 
                                                       (planning/discover-tasks (:catalog job-2) (:workflow job-2)))
                          {:fn :kill-job :args {:job job-2-id}}])
       :log []
       :peer-choices []}))]
   (is (= (apply + (map count (vals (get (:allocations replica) job-1-id)))) 8))
   (is (= (apply + (map count (vals (get (:allocations replica) job-2-id)))) 0))))

(deftest balanced-task-balancing
  (checking
   "Checking Balanced allocation causes peers to be evenly over tasks"
   1000
   [{:keys [replica log peer-choices]} 
    (log-gen/apply-entries-gen 
     (gen/return
      {:replica {:job-scheduler :onyx.job-scheduler/balanced
                 :messaging {:onyx.messaging/impl :dummy-messenger}}
       :message-id 0
       :entries (assoc (generate-join-entries (generate-peer-ids 6))
                  :job-1 [(api/create-submit-job-entry job-1-id
                                                       peer-config 
                                                       job-1 
                                                       (planning/discover-tasks (:catalog job-1) (:workflow job-1)))]
                  :job-2 [(api/create-submit-job-entry job-2-id
                                                       peer-config 
                                                       job-2 
                                                       (planning/discover-tasks (:catalog job-2) (:workflow job-2)))])
       :log []
       :peer-choices []}))]
   (is (= (map count (vals (get (:allocations replica) job-1-id))) [1 1 1]))
   (is (= (map count (vals (get (:allocations replica) job-2-id))) [1 1 1]))))

(deftest balanced-allocations-uneven
  (checking
   "Checking Balanced allocation causes peers to be evenly over tasks when the spread is uneven"
   1000
   [{:keys [replica log peer-choices]}
    (log-gen/apply-entries-gen
     (gen/return
      {:replica {:job-scheduler :onyx.job-scheduler/balanced
                 :messaging {:onyx.messaging/impl :dummy-messenger}}
       :message-id 0
       :entries (assoc (generate-join-entries (generate-peer-ids 7))
                  :job-1 [(api/create-submit-job-entry job-1-id
                                                       peer-config
                                                       job-1
                                                       (planning/discover-tasks (:catalog job-1) (:workflow job-1)))]
                  :job-2 [(api/create-submit-job-entry job-2-id
                                                       peer-config
                                                       job-2
                                                       (planning/discover-tasks (:catalog job-2) (:workflow job-2)))])
       :log []
       :peer-choices []}))]
   (let [j1-allocations (map (fn [t] (get-in replica [:allocations job-1-id t])) (get-in replica [:tasks job-1-id]))
         j2-allocations (map (fn [t] (get-in replica [:allocations job-2-id t])) (get-in replica [:tasks job-2-id]))]
     ;; Since job IDs are reused, we can't know which order they'll be in.
     (is (= (set [(map count j1-allocations) (map count j2-allocations)])
            #{[2 1 1] [1 1 1]})))))

(deftest balanced-allocations
  (checking
   "Checking balanced allocation causes peers to be evenly split"
   1000
   [{:keys [replica log peer-choices]} 
    (log-gen/apply-entries-gen 
     (gen/return
      {:replica {:job-scheduler :onyx.job-scheduler/balanced
                 :messaging {:onyx.messaging/impl :dummy-messenger}}
       :message-id 0
       :entries (assoc (generate-join-entries (generate-peer-ids 12))
                  :job-1 [(api/create-submit-job-entry job-1-id
                                                       peer-config 
                                                       job-1 
                                                       (planning/discover-tasks (:catalog job-1) (:workflow job-1)))]
                  :job-2 [(api/create-submit-job-entry job-2-id
                                                       peer-config 
                                                       job-2 
                                                       (planning/discover-tasks (:catalog job-2) (:workflow job-2)))]
                  :job-3 [(api/create-submit-job-entry job-3-id
                                                       peer-config 
                                                       job-3 
                                                       (planning/discover-tasks (:catalog job-3) (:workflow job-3)))
                          {:fn :kill-job :args {:job job-3-id}}])
       :log []
       :peer-choices []}))]
   (is (= (map count (vals (get (:allocations replica) job-1-id))) [2 2 2]))
   (is (= (map count (vals (get (:allocations replica) job-2-id))) [2 2 2]))
   (is (= (map count (vals (get (:allocations replica) job-3-id))) []))))
