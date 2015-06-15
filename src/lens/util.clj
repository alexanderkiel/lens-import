(ns lens.util
  (:require [clojure.core.async :refer [<! <!!]]))

(defn throw-err [e]
  (when (instance? Throwable e) (throw e))
  e)

(defmacro <? [ch]
  `(throw-err (<! ~ch)))

(defmacro <?? [ch]
  `(throw-err (<!! ~ch)))
