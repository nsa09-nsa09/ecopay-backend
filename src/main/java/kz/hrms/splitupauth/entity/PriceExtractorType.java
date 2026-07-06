package kz.hrms.splitupauth.entity;

/** Extraction recipe selector for a {@link PriceWatchProvider}. */
public enum PriceExtractorType {
  AUTO,
  JSON_LD,
  META,
  CSS,
  REGEX,
  MANUAL
}
