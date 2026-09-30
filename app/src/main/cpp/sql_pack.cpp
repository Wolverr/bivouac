#include "sql_pack.h"

#include <cstdio>

#include "json_util.h"
#include "sqlite3.h"

SqlPack::~SqlPack() { close(); }

std::string SqlPack::open(const std::string & path) {
    close();
    if (sqlite3_open_v2(path.c_str(), &db_, SQLITE_OPEN_READONLY | SQLITE_OPEN_NOMUTEX, nullptr) != SQLITE_OK) {
        std::string err = db_ ? sqlite3_errmsg(db_) : "sqlite open failed";
        close();
        return err;
    }
    sqlite3_exec(db_, "PRAGMA mmap_size=134217728; PRAGMA cache_size=-16384;", nullptr, nullptr, nullptr);
    return "";
}

void SqlPack::close() {
    if (db_) sqlite3_close_v2(db_);
    db_ = nullptr;
}

std::string SqlPack::query_json(const std::string & sql, const std::vector<std::string> & args, std::string & error) {
    error.clear();
    if (!db_) {
        error = "pack not open";
        return "[]";
    }
    sqlite3_stmt * st = nullptr;
    if (sqlite3_prepare_v2(db_, sql.c_str(), -1, &st, nullptr) != SQLITE_OK) {
        error = sqlite3_errmsg(db_);
        return "[]";
    }
    for (size_t i = 0; i < args.size(); i++) {
        sqlite3_bind_text(st, (int) i + 1, args[i].c_str(), -1, SQLITE_TRANSIENT);
    }
    std::string out = "[";
    const int ncol = sqlite3_column_count(st);
    int rc;
    bool first_row = true;
    while ((rc = sqlite3_step(st)) == SQLITE_ROW) {
        if (!first_row) out += ",";
        first_row = false;
        out += "[";
        for (int c = 0; c < ncol; c++) {
            if (c) out += ",";
            switch (sqlite3_column_type(st, c)) {
                case SQLITE_INTEGER:
                    out += std::to_string(sqlite3_column_int64(st, c));
                    break;
                case SQLITE_FLOAT: {
                    char buf[32];
                    snprintf(buf, sizeof(buf), "%.7g", sqlite3_column_double(st, c));
                    out += buf;
                    break;
                }
                case SQLITE_NULL:
                    out += "null";
                    break;
                default: {
                    const auto * p = reinterpret_cast<const char *>(sqlite3_column_text(st, c));
                    out += "\"" + json_escape(p ? std::string(p, sqlite3_column_bytes(st, c)) : "") + "\"";
                }
            }
        }
        out += "]";
    }
    if (rc != SQLITE_DONE) error = sqlite3_errmsg(db_);
    sqlite3_finalize(st);
    return out + "]";
}
