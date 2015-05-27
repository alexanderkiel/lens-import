(ns user
  (:use plumbing.core)
  (:require [clojure.core.async :as async :refer [<! >! <!! >!!]]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [org.httpkit.client :as http]
            [lens.api :as api]
            [lens.parse :refer [parse]]))

(def publisher (async/chan))
(def publication (async/pub publisher #(:topic %)))

(comment

  (api/execute-query "http://localhost:5001/find-study" {:id "S.0000"})

  (let [ch (async/chan)]
    (async/sub publication :study ch)
    (async/go-loop []
      (when-let [{:keys [msg]} (<! ch)]
        (api/create-or-update-study! msg)
        (recur))))

  (let [ch (async/chan)]
    (async/sub publication :form ch)
    (async/go-loop []
      (when-let [{:keys [msg]} (<! ch)]
        (api/create-or-update-form! msg)
        (recur))))

  (->> "samples/9814_who_five_well-being_.ODM.xml"
       #_"samples/9840_nci_standard_adverse.ODM.xml"
       (io/input-stream )
       (parse publisher)
       (<!!))

  )
