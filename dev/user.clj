(ns user
  (:use plumbing.core)
  (:require [clojure.core.async :as async :refer [<! >! >!!]]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [org.httpkit.client :as http]
            [cognitect.transit :as transit]
            [lens.parse :refer [parse]])
  (:import [java.net URI]))

(def publisher (async/chan))
(def publication (async/pub publisher #(:topic %)))

(defn parse-transit [is]
  (transit/read (transit/reader is :json)))

(defn resolve-uri [base-uri uri]
  (str (.resolve (URI/create base-uri) uri)))

(defn get-form [url params]
  (http/get url {:headers {"Accept" "application/transit+json"}
                 :query-params params :as :stream}))

(defn post-form [url params]
  (http/request {:method :post :url url :form-params params}))

(defnk create-study! [id name & more]
  (letk [[status]
         @(post-form "http://localhost:5001/studies"
                     (merge {"id" id "name" name}
                            (select-keys more [:description])))]
    (if (= 201 status)
      (println "Created study" id)
      (println "Failed creating study" id " status" status))))

(defnk update-study! [id uri etag name & more]
  (letk [[status]
         @(http/put uri {:headers {"If-Match" etag
                                   "Accept" "application/transit+json"
                                   "Content-Type" "application/json"}
                         :body (str "{\"name\": \"" name "\"}")})]
    (if (= 204 status)
      (println "Updated study" id)
      (println "Failed updating study" id " status" status))))

(defnk create-or-update-study! [id :as req]
  (let [uri "http://localhost:5001/find-study"
        resp @(get-form uri {"id" id})]
    (println )
    (if (= 200 (:status resp))
      (update-study! (assoc req :etag (-> resp :headers :etag)
                                :uri (->> resp :body parse-transit :links
                                          :self :href (resolve-uri uri))))
      (create-study! req))))

(defnk create-form! [id name study-id & more]
  (letk [[status {error nil}]
         @(post-form "http://localhost:5001/studies"
                     (merge {"id" id "name" name "study-id" study-id}
                            (select-keys more [:description])))]
    (if error
      (println "Failed creating study" id "error" error)
      (when (not= 201 status)
        (println "Failed creating study" id "status" status)))))

(comment

  (let [ch (async/chan)]
    (async/sub publication :study ch)
    (async/go-loop []
      (when-let [{:keys [msg]} (<! ch)]
        (create-or-update-study! msg)
        (recur))))

  (let [ch (async/chan)]
    (async/sub publication :form ch)
    (async/go-loop []
      (when-let [{:keys [msg]} (<! ch)]
        (pprint msg)
        (recur))))

  (parse publisher (io/input-stream "samples/9814_who_five_well-being_.ODM.xml"))
  )
