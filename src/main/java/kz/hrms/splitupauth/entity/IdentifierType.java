package kz.hrms.splitupauth.entity;

/**
 * What a room member handed over when joining. The telecom-flavoured values (SIM/ESIM/ACCOUNT)
 * predate {@link ServiceAccessType}; EMAIL was added for digital services that invite by address.
 */
public enum IdentifierType {
  PHONE,
  ACCOUNT,
  SIM,
  ESIM,
  EMAIL
}
