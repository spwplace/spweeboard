//! Persistent storage for ground contexts.

use crate::ground::Ground;
use rusqlite::{params, Connection, Result as SqlResult};
use std::path::Path;

/// SQLite-backed storage for ground contexts.
pub struct GroundStore {
    conn: Connection,
}

impl GroundStore {
    /// Opens or creates a ground store at the given path.
    ///
    /// # Errors
    /// Returns an error if the database cannot be opened or schema cannot be created.
    pub fn open(path: impl AsRef<Path>) -> SqlResult<Self> {
        let conn = Connection::open(path)?;
        let store = Self { conn };
        store.init_schema()?;
        Ok(store)
    }

    /// Creates an in-memory ground store (for testing).
    ///
    /// # Errors
    /// Returns an error if the database cannot be created.
    pub fn in_memory() -> SqlResult<Self> {
        let conn = Connection::open_in_memory()?;
        let store = Self { conn };
        store.init_schema()?;
        Ok(store)
    }

    /// Initializes the database schema.
    fn init_schema(&self) -> SqlResult<()> {
        self.conn.execute_batch(
            r"
            CREATE TABLE IF NOT EXISTS grounds (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                content_type TEXT NOT NULL CHECK (content_type IN ('natural', 'spw')),
                content TEXT NOT NULL,
                description TEXT NOT NULL DEFAULT '',
                category TEXT NOT NULL DEFAULT '',
                created_at TEXT DEFAULT (datetime('now')),
                updated_at TEXT DEFAULT (datetime('now'))
            );

            CREATE INDEX IF NOT EXISTS idx_grounds_name ON grounds(name);
            CREATE INDEX IF NOT EXISTS idx_grounds_category ON grounds(category);

            CREATE TABLE IF NOT EXISTS history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                expression TEXT NOT NULL,
                created_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now'))
            );

            CREATE INDEX IF NOT EXISTS idx_history_created ON history(created_at DESC);
            ",
        )?;
        Ok(())
    }

    /// Saves a ground to the store (upsert).
    ///
    /// # Errors
    /// Returns an error if the database operation fails.
    pub fn save(&self, ground: &Ground) -> SqlResult<()> {
        let (content_type, content) = match &ground.content {
            crate::ground::GroundContent::Natural(text) => ("natural", text.to_string()),
            crate::ground::GroundContent::Spw(expr) => ("spw", expr.render()),
        };

        self.conn.execute(
            r"
            INSERT INTO grounds (id, name, content_type, content, description, category, updated_at)
            VALUES (?1, ?2, ?3, ?4, ?5, ?6, datetime('now'))
            ON CONFLICT(id) DO UPDATE SET
                name = excluded.name,
                content_type = excluded.content_type,
                content = excluded.content,
                description = excluded.description,
                category = excluded.category,
                updated_at = datetime('now')
            ",
            params![
                ground.id.as_str(),
                ground.name.as_str(),
                content_type,
                content,
                ground.description.as_str(),
                ground.category.as_str()
            ],
        )?;
        Ok(())
    }

    /// Loads a ground by ID.
    ///
    /// # Errors
    /// Returns an error if the database operation fails or the ground doesn't exist.
    pub fn load(&self, id: &str) -> SqlResult<Option<Ground>> {
        let mut stmt = self.conn.prepare(
            "SELECT id, name, content_type, content, description, category FROM grounds WHERE id = ?1",
        )?;

        let mut rows = stmt.query(params![id])?;

        if let Some(row) = rows.next()? {
            let id: String = row.get(0)?;
            let name: String = row.get(1)?;
            let content_type: String = row.get(2)?;
            let content: String = row.get(3)?;
            let description: String = row.get(4)?;
            let category: String = row.get(5)?;

            let ground = match content_type.as_str() {
                "natural" => Ground::natural(&id, &name, &content, &description, &category),
                "spw" => Ground::spw(&id, &name, &content, &description, &category)
                    .unwrap_or_else(|_| Ground::natural(&id, &name, &content, &description, &category)),
                _ => Ground::natural(&id, &name, &content, &description, &category),
            };

            Ok(Some(ground))
        } else {
            Ok(None)
        }
    }

    /// Lists all grounds.
    ///
    /// # Errors
    /// Returns an error if the database operation fails.
    pub fn list(&self) -> SqlResult<Vec<Ground>> {
        let mut stmt = self.conn.prepare(
            "SELECT id, name, content_type, content, description, category FROM grounds ORDER BY updated_at DESC",
        )?;

        let rows = stmt.query_map([], |row| {
            let id: String = row.get(0)?;
            let name: String = row.get(1)?;
            let content_type: String = row.get(2)?;
            let content: String = row.get(3)?;
            let description: String = row.get(4)?;
            let category: String = row.get(5)?;

            let ground = match content_type.as_str() {
                "spw" => Ground::spw(&id, &name, &content, &description, &category)
                    .unwrap_or_else(|_| Ground::natural(&id, &name, &content, &description, &category)),
                _ => Ground::natural(&id, &name, &content, &description, &category),
            };

            Ok(ground)
        })?;

        rows.collect()
    }

    /// Deletes a ground by ID.
    ///
    /// # Errors
    /// Returns an error if the database operation fails.
    pub fn delete(&self, id: &str) -> SqlResult<bool> {
        let count = self.conn.execute("DELETE FROM grounds WHERE id = ?1", params![id])?;
        Ok(count > 0)
    }

    // =========================================================================
    // History Methods
    // =========================================================================

    /// Pushes an expression to history.
    ///
    /// Avoids consecutive duplicates (won't add if same as most recent).
    ///
    /// # Errors
    /// Returns an error if the database operation fails.
    pub fn push_history(&self, expression: &str) -> SqlResult<()> {
        // Check if same as most recent (use id for ordering, not timestamp)
        let last: Option<String> = self.conn.query_row(
            "SELECT expression FROM history ORDER BY id DESC LIMIT 1",
            [],
            |row| row.get(0),
        ).ok();

        if last.as_deref() == Some(expression) {
            return Ok(()); // Skip duplicate
        }

        self.conn.execute(
            "INSERT INTO history (expression) VALUES (?1)",
            params![expression],
        )?;

        // Trim to max 100 entries (keep newest by id)
        self.conn.execute(
            r"
            DELETE FROM history WHERE id NOT IN (
                SELECT id FROM history ORDER BY id DESC LIMIT 100
            )
            ",
            [],
        )?;

        Ok(())
    }

    /// Lists history entries (newest first).
    ///
    /// # Arguments
    /// * `limit` - Maximum number of entries to return (0 for all, up to 100).
    ///
    /// # Errors
    /// Returns an error if the database operation fails.
    pub fn list_history(&self, limit: u32) -> SqlResult<Vec<String>> {
        let limit = if limit == 0 { 100 } else { limit.min(100) };

        let mut stmt = self.conn.prepare(
            "SELECT expression FROM history ORDER BY id DESC LIMIT ?1",
        )?;

        let rows = stmt.query_map(params![limit], |row| row.get(0))?;
        rows.collect()
    }

    /// Recalls a specific history entry by index (0 = newest).
    ///
    /// # Errors
    /// Returns an error if the database operation fails.
    pub fn recall_history(&self, index: u32) -> SqlResult<Option<String>> {
        let mut stmt = self.conn.prepare(
            "SELECT expression FROM history ORDER BY id DESC LIMIT 1 OFFSET ?1",
        )?;

        let mut rows = stmt.query(params![index])?;
        match rows.next()? {
            Some(row) => Ok(Some(row.get(0)?)),
            None => Ok(None),
        }
    }

    /// Clears all history entries.
    ///
    /// # Errors
    /// Returns an error if the database operation fails.
    pub fn clear_history(&self) -> SqlResult<()> {
        self.conn.execute("DELETE FROM history", [])?;
        Ok(())
    }

    /// Returns the number of history entries.
    ///
    /// # Errors
    /// Returns an error if the database operation fails.
    pub fn history_count(&self) -> SqlResult<u32> {
        self.conn.query_row(
            "SELECT COUNT(*) FROM history",
            [],
            |row| row.get(0),
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip_natural_ground() {
        let store = GroundStore::in_memory().unwrap();
        let ground = Ground::natural("test", "Test Ground", "hello world", "Test description", "Test");

        store.save(&ground).unwrap();
        let loaded = store.load("test").unwrap().unwrap();

        assert_eq!(loaded.id, ground.id);
        assert_eq!(loaded.name, ground.name);
        assert_eq!(loaded.render(), "hello world");
        assert_eq!(loaded.description.as_str(), "Test description");
        assert_eq!(loaded.category.as_str(), "Test");
    }

    #[test]
    fn roundtrip_spw_ground() {
        let store = GroundStore::in_memory().unwrap();
        let ground = Ground::spw("spw-test", "SPW Ground", "@[work].{utility}", "SPW desc", "Work").unwrap();

        store.save(&ground).unwrap();
        let loaded = store.load("spw-test").unwrap().unwrap();

        assert_eq!(loaded.render(), "@[work].{utility}");
        assert_eq!(loaded.category.as_str(), "Work");
    }

    #[test]
    fn list_grounds() {
        let store = GroundStore::in_memory().unwrap();

        store.save(&Ground::natural("a", "A", "alpha", "Alpha desc", "Test")).unwrap();
        store.save(&Ground::natural("b", "B", "beta", "Beta desc", "Test")).unwrap();

        let grounds = store.list().unwrap();
        assert_eq!(grounds.len(), 2);
    }

    #[test]
    fn delete_ground() {
        let store = GroundStore::in_memory().unwrap();
        store.save(&Ground::natural("del", "Delete Me", "goodbye", "Delete desc", "Test")).unwrap();

        assert!(store.delete("del").unwrap());
        assert!(store.load("del").unwrap().is_none());
    }

    #[test]
    fn push_and_list_history() {
        let store = GroundStore::in_memory().unwrap();

        store.push_history("&@").unwrap();
        store.push_history("~!").unwrap();
        store.push_history("?*").unwrap();

        let history = store.list_history(10).unwrap();
        assert_eq!(history.len(), 3);
        // Newest first
        assert_eq!(history[0], "?*");
        assert_eq!(history[2], "&@");
    }

    #[test]
    fn history_no_consecutive_duplicates() {
        let store = GroundStore::in_memory().unwrap();

        store.push_history("&@").unwrap();
        store.push_history("&@").unwrap();
        store.push_history("&@").unwrap();

        assert_eq!(store.history_count().unwrap(), 1);
    }

    #[test]
    fn recall_history() {
        let store = GroundStore::in_memory().unwrap();

        store.push_history("first").unwrap();
        store.push_history("second").unwrap();
        store.push_history("third").unwrap();

        assert_eq!(store.recall_history(0).unwrap(), Some("third".to_string()));
        assert_eq!(store.recall_history(2).unwrap(), Some("first".to_string()));
        assert_eq!(store.recall_history(10).unwrap(), None);
    }

    #[test]
    fn clear_history() {
        let store = GroundStore::in_memory().unwrap();

        store.push_history("a").unwrap();
        store.push_history("b").unwrap();
        assert!(store.history_count().unwrap() > 0);

        store.clear_history().unwrap();
        assert_eq!(store.history_count().unwrap(), 0);
    }
}
