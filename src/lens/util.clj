(ns lens.util
  (:require [clojure.core.async :refer [<! <!! go]]))

(defn throw-err [e]
  (when (instance? Throwable e) (throw e))
  e)

(defmacro <? [ch]
  `(throw-err (<! ~ch)))

(defmacro <?? [ch]
  `(throw-err (<!! ~ch)))

(defmacro try-go [& body]
  `(go
     (try
       ~@body
       (catch Throwable t# t#))))
