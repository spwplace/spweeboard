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
                created_at TEXT DEFAULT (datetime('now')),
                updated_at TEXT DEFAULT (datetime('now'))
            );

            CREATE INDEX IF NOT EXISTS idx_grounds_name ON grounds(name);
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
            INSERT INTO grounds (id, name, content_type, content, updated_at)
            VALUES (?1, ?2, ?3, ?4, datetime('now'))
            ON CONFLICT(id) DO UPDATE SET
                name = excluded.name,
                content_type = excluded.content_type,
                content = excluded.content,
                updated_at = datetime('now')
            ",
            params![ground.id.as_str(), ground.name.as_str(), content_type, content],
        )?;
        Ok(())
    }

    /// Loads a ground by ID.
    ///
    /// # Errors
    /// Returns an error if the database operation fails or the ground doesn't exist.
    pub fn load(&self, id: &str) -> SqlResult<Option<Ground>> {
        let mut stmt = self.conn.prepare(
            "SELECT id, name, content_type, content FROM grounds WHERE id = ?1",
        )?;

        let mut rows = stmt.query(params![id])?;

        if let Some(row) = rows.next()? {
            let id: String = row.get(0)?;
            let name: String = row.get(1)?;
            let content_type: String = row.get(2)?;
            let content: String = row.get(3)?;

            let ground = match content_type.as_str() {
                "natural" => Ground::natural(&id, &name, &content),
                "spw" => Ground::spw(&id, &name, &content)
                    .unwrap_or_else(|_| Ground::natural(&id, &name, &content)),
                _ => Ground::natural(&id, &name, &content),
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
            "SELECT id, name, content_type, content FROM grounds ORDER BY updated_at DESC",
        )?;

        let rows = stmt.query_map([], |row| {
            let id: String = row.get(0)?;
            let name: String = row.get(1)?;
            let content_type: String = row.get(2)?;
            let content: String = row.get(3)?;

            let ground = match content_type.as_str() {
                "spw" => Ground::spw(&id, &name, &content)
                    .unwrap_or_else(|_| Ground::natural(&id, &name, &content)),
                _ => Ground::natural(&id, &name, &content),
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
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip_natural_ground() {
        let store = GroundStore::in_memory().unwrap();
        let ground = Ground::natural("test", "Test Ground", "hello world");

        store.save(&ground).unwrap();
        let loaded = store.load("test").unwrap().unwrap();

        assert_eq!(loaded.id, ground.id);
        assert_eq!(loaded.name, ground.name);
        assert_eq!(loaded.render(), "hello world");
    }

    #[test]
    fn roundtrip_spw_ground() {
        let store = GroundStore::in_memory().unwrap();
        let ground = Ground::spw("spw-test", "SPW Ground", "@[work].{utility}").unwrap();

        store.save(&ground).unwrap();
        let loaded = store.load("spw-test").unwrap().unwrap();

        assert_eq!(loaded.render(), "@[work].{utility}");
    }

    #[test]
    fn list_grounds() {
        let store = GroundStore::in_memory().unwrap();

        store.save(&Ground::natural("a", "A", "alpha")).unwrap();
        store.save(&Ground::natural("b", "B", "beta")).unwrap();

        let grounds = store.list().unwrap();
        assert_eq!(grounds.len(), 2);
    }

    #[test]
    fn delete_ground() {
        let store = GroundStore::in_memory().unwrap();
        store.save(&Ground::natural("del", "Delete Me", "goodbye")).unwrap();

        assert!(store.delete("del").unwrap());
        assert!(store.load("del").unwrap().is_none());
    }
}
