-- C8: V95 seeded search_aliases with lower(name) IN ('youtube' / 'beeline' / 'activ') but the
-- actual catalog rows are named "YouTube Premium", "Beeline Family", "Activ Family", so those
-- three UPDATEs matched nothing and /catalog/search?q=ютуб returned empty. Re-seed by slug
-- (stable identifier, matches regardless of localised display name) and only when the row still
-- has no aliases, so an admin edit after V95 is preserved.

UPDATE services
   SET search_aliases = 'ютуб, ютюб, ютьюб, youtube, yt'
 WHERE lower(slug) LIKE 'youtube%'
   AND search_aliases IS NULL;

UPDATE services
   SET search_aliases = 'билайн, beeline'
 WHERE lower(slug) LIKE 'beeline%'
   AND search_aliases IS NULL;

UPDATE services
   SET search_aliases = 'актив, activ, kcell'
 WHERE lower(slug) LIKE 'activ%'
   AND search_aliases IS NULL;
