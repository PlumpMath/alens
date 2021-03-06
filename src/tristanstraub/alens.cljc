(ns tristanstraub.alens
  #?(:cljs (:require-macros [cljs.core.async.macros :as a]))
  (:require #?(:clj [clojure.core.async :as a]
               :cljs [cljs.core.async :as a])))

(defn projector%
  [fapply fjoin]
  (fn
    ([x l] (fapply (l fapply fjoin) x))
    ([x l f] (fapply (l fapply fjoin) f x))))

(defn projector
  [fapply fjoin]
  (fn
    ([x l] ((projector% fapply fjoin) x (l)))
    ([x l f] ((projector% fapply fjoin) x (l) f))))

(defn composable [outer]
  (fn
    ([] outer)
    ([inner]
     (fn [fapply fjoin]
       (let [p (projector% fapply fjoin)]
         (fn
           ([x] (-> x (p outer) (p inner)))
           ([f x] (p x outer #(p % inner f)))))))))

(defn lift2 [l]
  (composable (fn
          ([] l)
          ([fapply fjoin] l))))

(defn id
;;  ([x] x)
  ([f & xs] (apply f xs)))

(defn at
  ([k]
   (composable (fn [fapply fjoin]
                 (fn cb
                   ([x] (get x k))
                   ([f x]
                    (fapply #(assoc %1 k %2) x (fapply f (cb x))))))))
  ([k & ks]
   (if (not (empty? ks))
     (comp (at k) (apply at ks))
     (at k))))

(defn read-port? [ch]
  (satisfies? #?(:clj clojure.core.async.impl.protocols/ReadPort
                 :cljs cljs.core.async.impl.protocols/ReadPort)
              ch))


(defn async-fjoin [xs]
  (a/go
    (let [xs     (loop [mxs []
                        xs  xs]
                   (if xs
                     (do
                       (let [[x & xs] xs]
                         (recur (conj mxs (if (read-port? x)
                                            (a/<! x)
                                            x))
                                xs)))
                     mxs))
          result xs]
      (if (read-port? result)
        (a/<! result)
        result))))

(defn async-fapply [fjoin]
  (fn
    ([f & xs]
     (a/go
       (let [result (apply f (a/<! (fjoin xs)))]
         (if (read-port? result)
           (a/<! result)
           result))))))

;; (defn fmap [fapply fjoin]
;;   (fn [f x]
;;     (fapply fjoin (map #(fapply f %) x))))

(def leaves
  (composable
   (fn [fapply fjoin]
     (fn cb
       ([x]
        (cond (map? x)
              (fapply #(apply concat %) (->> (vals x)
                                             (map #(fapply cb %))
                                             fjoin))
              :else
              [x]))
       ([f x]
        (cond (map? x)
              (->> x
                   (vals)
                   (map #(fapply cb f %))
                   fjoin
                   (fapply zipmap (keys x)))
              :else
              (fapply f x)))))))

(defn fwhen [pred?]
  (composable
   (fn [fapply fjoin]
     (fn
       ([x] x)
       ([f x] (if (pred? x) (fapply f x) x))))))

(def project (projector id seq))
(def project-async (projector (async-fapply async-fjoin) async-fjoin))
