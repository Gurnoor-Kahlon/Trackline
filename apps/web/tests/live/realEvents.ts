/**
 * Events recorded from the live API on 2026-10-07, trimmed to three vehicles each.
 *
 * Real output of the server, not hand-written JSON, so the parser is checked against
 * what is actually sent. If the server changes its wire format these stop parsing,
 * which is the point of keeping them.
 */

export const REAL_SNAPSHOT = "{\"serverTime\": \"2026-10-07T21:43:07.153187600Z\", \"feed\": {\"timestamp\": \"2026-10-07T21:42:18Z\", \"ageSeconds\": 49}, \"fields\": [\"id\", \"route\", \"lat\", \"lon\", \"bearing\", \"speed\", \"occupancy\", \"timestamp\"], \"vehicles\": [[\"4501\", \"501\", 43.635773, -79.473236, 217, 0.0, 0, 1791409302], [\"4537\", \"501\", 43.64706, -79.404373, 74, 0.0, 0, 1791409303], [\"4543\", \"501\", 43.655689, -79.364281, 252, 1.8, 0, 1791409307]]}";

export const REAL_DELTA = "{\"serverTime\": \"2026-10-07T21:43:21.418254600Z\", \"feed\": {\"timestamp\": \"2026-10-07T21:43:12Z\", \"ageSeconds\": 9}, \"changed\": [[\"4501\", \"501\", 43.632126, -79.477661, 246, 7.6, 0, 1791409372], [\"4537\", \"501\", 43.64761, -79.40197, 75, 6.7, 0, 1791409363], [\"4543\", \"501\", 43.655163, -79.366814, 253, 0.0, 0, 1791409368]], \"removed\": []}";

export const REAL_HEARTBEAT = "{\"serverTime\":\"2026-10-07T21:43:16.099017700Z\",\"feed\":{\"timestamp\":\"2026-10-07T21:42:18Z\",\"ageSeconds\":58},\"latestEventId\":1791409380924}";
