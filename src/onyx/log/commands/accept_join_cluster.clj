(ns onyx.log.commands.accept-join-cluster
  (:require [clojure.core.async :refer [chan go >! <! >!! close!]]
            [clojure.data :refer [diff]]
            [taoensso.timbre :refer [info] :as timbre]
            [onyx.extensions :as extensions]
            [onyx.log.commands.common :as common]
            [onyx.scheduling.common-job-scheduler :refer [reconfigure-cluster-workload]]))

(defmethod extensions/apply-log-entry :accept-join-cluster
  [{:keys [args]} replica]
  (let [{:keys [accepted-joiner accepted-observer]} args
        target (or (get-in replica [:pairs accepted-observer])
                   accepted-observer)]
    (-> replica
        (update-in [:pairs] merge {accepted-observer accepted-joiner})
        (update-in [:pairs] merge {accepted-joiner target})
        (update-in [:accepted] dissoc accepted-observer)
        (update-in [:peers] vec)
        (update-in [:peers] conj accepted-joiner)
        (assoc-in [:peer-state accepted-joiner] :idle)
        (reconfigure-cluster-workload))))

(defmethod extensions/replica-diff :accept-join-cluster
  [entry old new]
  (let [rets (first (diff (:accepted old) (:accepted new)))]
    (assert (<= (count rets) 1))
    (when (seq rets)
      {:observer (first (keys rets))
       :subject (first (vals rets))})))

(defmethod extensions/reactions :accept-join-cluster
  [entry old new diff state]
  [])

(defn unbuffer-messages [state diff new]
  (if (= (:id state) (:subject diff))
    (do (extensions/open-peer-site (:messenger state) 
                                   (get-in new [:peer-sites (:id state)]))
        (doseq [entry (:buffered-outbox state)]
          (>!! (:outbox-ch state) entry))
        (assoc (dissoc state :buffered-outbox) :stall-output? false))
    state))

(defmethod extensions/fire-side-effects! :accept-join-cluster
  [entry old new diff state]
  (let [next-state (unbuffer-messages state diff new)]
    (common/start-new-lifecycle old new diff next-state)))
