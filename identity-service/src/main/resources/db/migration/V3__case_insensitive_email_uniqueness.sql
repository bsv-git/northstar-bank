UPDATE users
SET email = lower(trim(email))
WHERE email <> lower(trim(email));

CREATE UNIQUE INDEX uk_users_email_normalized
    ON users (lower(email));
