CREATE TABLE users(id varchar(40) PRIMARY KEY, name varchar(100) NOT NULL, email varchar(160) UNIQUE NOT NULL, password_hash varchar(100) NOT NULL, role varchar(20) NOT NULL, failures int NOT NULL DEFAULT 0, locked_until timestamptz);
CREATE TABLE sessions(token_hash varchar(64) PRIMARY KEY, user_id varchar(40) REFERENCES users(id), expires_at timestamptz NOT NULL);
CREATE INDEX sessions_expiry ON sessions(expires_at);
