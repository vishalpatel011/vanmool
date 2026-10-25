-- Login tokens are now signed JWTs. Each row's id is the "jti" claim of one JWT, and logout marks it revoked.
-- The JWT itself is never stored (its signature proves it is genuine), so token_hash goes, together with its
-- unique index. Tokens handed out before this change are removed: they are not JWTs and can no longer be used.

DELETE FROM token;

ALTER TABLE token
    DROP COLUMN token_hash;
