-- Accounts whose initial password was set by an operator (bootstrap ADMIN) must change it on first use.
ALTER TABLE account ADD COLUMN must_change_password boolean DEFAULT false NOT NULL;
