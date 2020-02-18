(ns superlifter.core
  (:require [urania.core :as u]
            [promesa.core :as prom]
            [clojure.tools.logging :as log])
  (:refer-clojure :exclude [resolve]))

(defprotocol Cache
  (->urania [this])
  (urania-> [this new-value]))

(extend-protocol Cache
  clojure.lang.Atom
  (->urania [this]
    (deref this))
  (urania-> [this new-value]
    (reset! this new-value)))

(def default-bucket-id :default)

(defn- bucket-for [context bucket-id]
  (let [buckets @(:buckets context)]
    (get buckets bucket-id
         (get buckets default-bucket-id))))

(defn enqueue!
  "Enqueues a muse describing work to be done and returns a promise which will be delivered with the result of the work.
   The muses in the queue will all be fetched together when the trigger condition is met."
  ([context muse] (enqueue! context default-bucket-id muse))
  ([context bucket-id muse]
   (let [bucket (bucket-for context bucket-id)
         p (prom/deferred)]
     (swap! (:queue bucket)
            conj (u/map (fn [result]
                          (prom/resolve! p result)
                          result)
                        muse))
     p)))

(defn- fetch-bucket! [bucket]
  (log/debug "Fetching bucket" bucket)
  (let [[muses] (reset-vals! (:queue bucket) [])
        cache (get-in bucket [:urania-opts :cache])]
    (when (pos? (count muses))
      (log/debug "Fetching" (count muses) "muses")
      (-> (u/execute! (u/collect muses)
                      (merge (:urania-opts bucket)
                             (when cache
                               {:cache (->urania cache)})))
          (prom/then
           (fn [[result new-cache-value :as r]]
             (when cache
               (urania-> cache new-cache-value))
             result))))))

(defn fetch!
  "Performs a fetch of all muses in the queue"
  ([context] (fetch! context default-bucket-id))
  ([context bucket-id]
   (let [bucket (bucket-for context bucket-id)]
     (fetch-bucket! bucket))))

(defn fetch-all! [context]
  (doseq [bucket (vals @(:buckets context))]
    (fetch-bucket! bucket)))

(defmulti start-trigger! (fn [bucket kind opts] kind))

(defmethod start-trigger! :queue-size [bucket _ opts]
  (let [threshold (:threshold opts)
        watch-id ::queue-size]
    (add-watch (:queue bucket)
               watch-id
               (fn [_ _ _ new-state]
                 (log/debug "Watching queue size" threshold (count new-state) bucket)
                 (when (>= (count new-state) threshold)
                   (log/debug "Going to fetch" bucket)
                   (future (try (prom/catch (fetch-bucket! bucket)
                                    (fn [error]
                                      (log/warn "Fetch failed" error)))
                                (catch Throwable t
                                  (log/warn "Fetch failed" t)))))))
    (assoc opts :stop-fn #(remove-watch (:queue bucket) watch-id))))

(defmethod start-trigger! :interval [bucket _ opts]
  (let [watcher (future (loop []
                          (Thread/sleep (:interval opts))
                          (try
                            (prom/catch (fetch-bucket! bucket)
                                (fn [error]
                                  (log/warn "Fetch failed" error)))
                            (catch Throwable t
                              (log/warn "Fetch failed" t)))
                          (recur)))]
    (assoc opts :stop-fn #(future-cancel watcher))))

(defmethod start-trigger! :default [bucket _ opts]
  opts)

(defn- start-triggers! [{:keys [triggers] :as bucket}]
  (update bucket :triggers
          #(do
             (log/debug "Starting" (count triggers) "triggers")
             (reduce-kv (fn [ts trigger-kind trigger-opts]
                          (log/debug "Starting trigger" trigger-kind trigger-opts)
                          (assoc ts trigger-kind (start-trigger! bucket trigger-kind trigger-opts)))
                        {}
                        %))))

(defn- start-bucket! [context id opts]
  (-> opts
      (assoc :queue (atom [])
             :id id)
      (update :urania-opts #(merge (:urania-opts context) %))
      start-triggers!))

(defn- start-buckets! [{:keys [buckets] :as context}]
  (swap! buckets
         #(reduce-kv (fn [buckets id opts]
                       (log/debug "Starting bucket" id)
                       (update buckets id (partial start-bucket! context id)))
                     %
                     %))
  context)

(defn add-bucket! [context id opts]
  (swap! (:buckets context)
         #(assoc % id (start-bucket! context id opts)))
  context)

(defn then-add-bucket! [p context id opts-fn]
  (prom/then
   p
   (fn [result]
     (add-bucket! context id (opts-fn result))
     result)))

(defn default-opts []
  {:urania-opts {:cache (atom {})}})

(defn start!
  "Starts a superlifter with the supplied options, which can contain:

  :buckets {:default bucket-opts
            :my-bucket bucket-opts
            ...}

  :urania-opts The options map supplied to urania for running muses.
               Contains :env, :cache and :executor
               :cache must implement the Cache protocol
               See urania documentation for details

  The `:default` bucket is used for all activity not associated with a named bucket.

  Bucket options can contain the following:

  :triggers    Conditions to perform a fetch of all muses in the queue.
               Triggers is a map of trigger-kind to trigger, looking like:

               {:queue-size {:threshold 10}
                :interval   {:interval 100}

               The fetch will be performed whenever any single trigger condition is met.

               Triggers can be of several types:

               Queue size trigger, which performs the fetch when the queue reaches n items
               {:queue-size {:threshold n}}

               Interval trigger, which performs the fetch every n milliseconds
               {:interval {:interval n}}

               If no triggers are supplied, superlifter runs in 'manual' mode and fetches will only be performed when you call `fetch!`

               You can supply your own trigger definition by participating in the `start-trigger!` multimethod.

  :urania-opts Override the top-level urania-opts at the bucket level


  Returns a context which can be used to stop superlifter, enqueue muses and trigger fetches.
  "
  [opts]
  (-> (merge (default-opts) opts)
      (update-in [:buckets default-bucket-id] #(or % {}))
      (update :buckets atom)
      (start-buckets!)))

(defn stop!
  "Stops superlifter"
  [context]
  (doseq [bucket (vals @(:buckets context))
          {:keys [stop-fn]} (vals (:triggers bucket))
          :when stop-fn]
    (stop-fn))
  context)
