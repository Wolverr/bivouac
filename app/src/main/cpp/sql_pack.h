#pragma once

#include <string>
#include <vector>

struct sqlite3;

// A read-only SQLite knowledge pack queried with plain SQL (used for the places
// pack). Results come back as JSON: [[col, ...], ...] with numbers unquoted.
class SqlPack {
public:
    ~SqlPack();
    std::string open(const std::string & path);  // "" on success, else error
    void close();
    bool is_open() const { return db_ != nullptr; }

    // Binds `args` as text parameters ?1, ?2, ... (SQLite converts for numeric comparisons
    // only when the column has numeric affinity, so callers CAST where needed).
    std::string query_json(const std::string & sql, const std::vector<std::string> & args, std::string & error);

private:
    sqlite3 * db_ = nullptr;
};
