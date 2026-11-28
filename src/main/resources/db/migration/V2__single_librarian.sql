CREATE UNIQUE INDEX users_single_librarian_idx ON users (role) WHERE role = 'LIBRARIAN';
