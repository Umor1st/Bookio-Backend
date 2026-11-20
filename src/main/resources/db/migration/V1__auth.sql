CREATE TABLE users (
 id UUID PRIMARY KEY, login VARCHAR(32) NOT NULL UNIQUE CHECK (login ~ '^[a-z0-9._-]{3,32}$'),
 password_hash VARCHAR(60) NOT NULL CHECK (length(password_hash) = 60),
 last_name VARCHAR(100) NOT NULL CHECK (length(btrim(last_name)) BETWEEN 1 AND 100 AND last_name = btrim(last_name)),
 first_name VARCHAR(100) NOT NULL CHECK (length(btrim(first_name)) BETWEEN 1 AND 100 AND first_name = btrim(first_name)),
 middle_name VARCHAR(100) CHECK (middle_name IS NULL OR (length(btrim(middle_name)) BETWEEN 1 AND 100 AND middle_name = btrim(middle_name))),
 grade SMALLINT, class_letter VARCHAR(5),
 role VARCHAR(16) NOT NULL CHECK (role IN ('STUDENT', 'LIBRARIAN')),
 status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'ACTIVE', 'REJECTED')),
 created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL,
 CHECK ((role = 'STUDENT' AND grade IS NOT NULL AND grade BETWEEN 1 AND 11 AND class_letter IS NOT NULL AND length(btrim(class_letter)) BETWEEN 1 AND 5 AND class_letter = btrim(class_letter)) OR (role = 'LIBRARIAN' AND grade IS NULL AND class_letter IS NULL))
);
CREATE TABLE sessions (
 id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES users(id),
 token_hash VARCHAR(64) NOT NULL UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$'),
 created_at TIMESTAMPTZ NOT NULL, expires_at TIMESTAMPTZ NOT NULL, revoked_at TIMESTAMPTZ,
 CHECK (expires_at > created_at)
);
CREATE INDEX sessions_user_id_idx ON sessions(user_id);
