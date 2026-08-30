package kz.hrms.splitupauth.entity;

/**
 * What a room member handed over when joining. The telecom-flavoured values (SIM/ESIM/ACCOUNT)
 * predate {@link ServiceAccessType} and are kept for legacy history; new joins only accept EMAIL or
 * PHONE according to the service access type.
 */
public enum IdentifierType {
  PHONE,
  ACCOUNT,
  SIM,
  ESIM,
  EMAIL
}
