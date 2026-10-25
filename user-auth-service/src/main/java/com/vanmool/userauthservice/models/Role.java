package com.vanmool.userauthservice.models;

// What a user may do. Every new account is a CUSTOMER; SELLER (a producer who lists products) and ADMIN are
// granted separately. An enum, not a table: each role has code behind it, so a role added only in the database
// would do nothing.
public enum Role {
    CUSTOMER,
    SELLER,
    ADMIN
}
