package edu.escuelaing.techcup.identity.domain;

/**
 * Relationship of a user with the school. Informational only: any e-mail may register, and
 * only an ADMIN may change it after registration.
 */
public enum SchoolRelation {
    STUDENT,
    PROFESSOR,
    ADMINISTRATIVE,
    GRADUATE,
    FAMILY
}
