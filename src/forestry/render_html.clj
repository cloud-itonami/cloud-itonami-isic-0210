(ns forestry.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300,
  Wave5). Drives the REAL actor stack (`forestry.operation` ->
  `forestry.governor` -> `forestry.store`) through a scenario adapted from
  this repo's own `forestry.sim` demo driver, rendered deterministically --
  no invented numbers/ids/ops.

  Usage: `clojure -M:dev:render-html [out-file]`."
  (:require [clojure.string :as str]
            [forestry.store :as store]
            [forestry.operation :as op]
            [langgraph.graph :as g]))

(def ^:private coordinator
  {:actor-id "coord-1" :actor-role :forestry-coordinator :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context coordinator} {:thread-id tid}))
(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}} {:thread-id tid :resume? true}))

(defn run-demo!
  "Seeds the stand directory then runs a representative mix: stand-001's
  clean stand-record (phase-3 auto-commit), a thinning schedule and a
  forest-health concern (both human-approved), plus two HARD holds -- a
  mis-wired :effect on stand-002 and a record against the UNVERIFIED
  stand-003. Every id/op/value is from forestry.sim / governor / store."
  []
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)]
    (exec! actor "t1" {:op :log-stand-record :effect :propose :subject "stand-001"
                       :patch {:health-status :healthy :last-assessed "2026-07-14"}})
    (exec! actor "t2" {:op :schedule-field-operation :effect :propose :subject "op-1"
                       :value {:stand-id "stand-001" :operation-type :thinning
                               :scheduled-date "2026-08-01" :finalize? false}})
    (approve! actor "t2")
    (exec! actor "t3" {:op :flag-forest-health-concern :effect :propose :subject "concern-1"
                       :value {:stand-id "stand-001" :severity :moderate
                               :description "bark-beetle-suspected"}})
    (approve! actor "t3")
    (exec! actor "t4" {:op :log-stand-record :effect :direct-write :subject "stand-002"
                       :patch {:health-status :healthy}})
    (exec! actor "t5" {:op :log-stand-record :effect :propose :subject "stand-003"
                       :patch {:health-status :healthy :last-assessed "2026-07-14"}})
    db))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- last-fact-for [ledger sid]
  (last (filter #(= (:subject %) sid) ledger)))

(defn- status-cell [ledger sid]
  (let [f (last-fact-for ledger sid)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-granted (:t f)) "<span class=\"ok\">approved &amp; committed</span>"
      (= :governor-hold (:t f))
      (let [rule (-> f :basis first)]
        (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>"))
      (= :approval-requested (:t f)) "<span class=\"warn\">awaiting approval</span>"
      :else "<span class=\"muted\">in progress</span>")))

(defn- stand-row [ledger {:keys [id site species area-ha health-status]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc id) (esc site) (esc (str species)) (esc (str (or area-ha "—")))
          (esc (name (or health-status :n-a))) (status-cell ledger id)))

(defn- ledger-row [{:keys [t op subject disposition basis]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (name t)) (esc (name (or op :n-a))) (esc subject)
          (esc (or (some->> basis (map name) (str/join ", ")) (some-> disposition name) ""))))

(def ^:private action-gate-rows
  ["        <tr><td><code>:log-stand-record</code></td><td><span class=\"ok\">auto-commit when clean + verified stand</span></td></tr>"
   "        <tr><td><code>:schedule-field-operation</code></td><td><span class=\"warn\">ALWAYS human approval; harvest-finalize permanently blocked</span></td></tr>"
   "        <tr><td><code>:flag-forest-health-concern</code></td><td><span class=\"warn\">ALWAYS human approval (forest safety)</span></td></tr>"
   "        <tr><td><code>:order-supplies</code></td><td><span class=\"warn\">human approval over cost threshold; total recompute</span></td></tr>"])

(defn render [db]
  (let [ledger (vec (store/ledger db))
        stands (->> (store/all-stands db) (sort-by :id))
        stand-rows (str/join "\n" (map (partial stand-row ledger) stands))
        ledger-rows (str/join "\n" (map ledger-row ledger))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isic-0210 &middot; forestry coordination</title><style>"
     "body{font:14px/1.5 -apple-system,system-ui,sans-serif;margin:0;color:#1a1a1a;background:#f5f5f5}"
     ".bar{background:#0d2b1e;color:#fff;padding:1.2rem 2rem}.bar h1{margin:0;font-size:1.15rem;font-weight:600}"
     ".badge{display:inline-block;margin-top:.4rem;font-size:.75rem;opacity:.8}"
     "main{max-width:980px;margin:1.5rem auto;padding:0 1rem}"
     ".card{background:#fff;border-radius:8px;padding:1.2rem 1.4rem;margin-bottom:1.2rem;box-shadow:0 1px 3px rgba(0,0,0,.08)}"
     ".card h2{margin-top:0;font-size:1rem}.muted{color:#777;font-size:.82rem}"
     "table{border-collapse:collapse;width:100%;font-size:.85rem}th,td{text-align:left;padding:.42rem .5rem;border-bottom:1px solid #eee}th{font-weight:600;color:#555}"
     ".ok{color:#0a7d33}.warn{color:#9a6700}.critical{color:#b41010;font-weight:600}code{background:#f0f0f0;padding:.1rem .3rem;border-radius:3px;font-size:.8rem}"
     "</style></head><body>\n"
     "<header class=\"bar\">\n  <h1>Forestry coordination (ISIC 0210) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · field-op / health / supply actuation always human-approved · harvest-finalize permanently blocked</span>\n</header>\n"
     "<main>\n  <section class=\"card\">\n    <h2>Logging stands</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>forestry.store</code> via <code>forestry.render-html</code> (<code>clojure -M:dev:render-html</code>), regenerated nightly. No invented data.</p>\n"
     "    <table>\n      <thead><tr><th>Stand</th><th>Site</th><th>Species</th><th>Area (ha)</th><th>Health</th><th>Last op status</th></tr></thead>\n      <tbody>\n"
     stand-rows "\n      </tbody>\n    </table>\n  </section>\n"
     "  <section class=\"card\">\n    <h2>Action gate (Forestry Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden. Stands must be verified before any field operation; harvest-finalize is permanently out of scope.</p>\n"
     "    <table>\n      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n      </tbody>\n    </table>\n  </section>\n"
     "  <section class=\"card\">\n    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — every proposal, hold and commit this scenario produced.</p>\n"
     "    <table>\n      <thead><tr><th>Fact</th><th>Op</th><th>Subject</th><th>Basis</th></tr></thead>\n      <tbody>\n"
     ledger-rows "\n      </tbody>\n    </table>\n  </section>\n"
     "</main>\n</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        out-file (java.io.File. out)]
    (.. out-file getParentFile mkdirs)
    (spit out-file (render db))
    (println "wrote" out "(" (count (store/ledger db)) "ledger facts )")))
