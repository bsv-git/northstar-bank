CREATE TABLE pending_signups(
 email varchar(160) PRIMARY KEY,
 name varchar(100) NOT NULL,
 password_hash varchar(100) NOT NULL,
 code_digest varchar(64) NOT NULL,
 expires_at timestamptz NOT NULL,
 attempts int NOT NULL DEFAULT 0,
 last_sent_at timestamptz NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX pending_signups_expiry ON pending_signups(expires_at);
