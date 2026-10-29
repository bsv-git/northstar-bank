CREATE TABLE notifications(sequence bigserial PRIMARY KEY, event_id uuid NOT NULL, user_id varchar(40) NOT NULL, title varchar(80) NOT NULL, message varchar(240) NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), read_at timestamptz, UNIQUE(event_id,user_id));
CREATE INDEX notifications_user ON notifications(user_id,sequence DESC);
