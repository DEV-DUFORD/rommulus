// test_player_validation.cpp — section 12.4 validation: request file
// ownership/mode, path canonicalization, trusted-root containment,
// `..` traversal and absolute-path escape rejection, symlink rejection,
// core allowlist, content hash, session lock, and valid-request
// acceptance.
#include "native/player/validation.h"

#include "romm_test.h"

#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <string>

using romm::player::PlayerConfig;
using romm::player::PlayerRequest;
using romm::player::TrustedRoots;
using romm::player::ValidationOutcome;
using romm::player::canonicalPath;
using romm::player::validateRequest;
using romm::player::validateRequestFile;

namespace {

std::string makeTempDir() {
    char templatePath[] = "/tmp/romm_player_test_XXXXXX";
    if (mkdtemp(templatePath) == nullptr) {
        std::fprintf(stderr, "fatal: mkdtemp failed\n");
        std::exit(2);
    }
    return templatePath;
}

bool makeDirs(const std::string& path) {
    if (path.empty() || path == "/") return true;
    std::string parent;
    auto slash = path.find_last_of('/');
    parent = (slash == std::string::npos) ? "" : path.substr(0, slash);
    if (!parent.empty() && parent != "/" && !makeDirs(parent)) return false;
    if (mkdir(path.c_str(), 0755) != 0) {
        struct stat st {};
        if (stat(path.c_str(), &st) != 0 || !S_ISDIR(st.st_mode)) return false;
    }
    return true;
}

bool writeFile(const std::string& path, const std::string& contents) {
    std::ofstream out(path, std::ios::binary | std::ios::trunc);
    if (!out) return false;
    out << contents;
    return static_cast<bool>(out);
}

bool makeSymlink(const std::string& target, const std::string& link) {
    ::unlink(link.c_str());
    return symlink(target.c_str(), link.c_str()) == 0;
}

struct Fixture {
    std::string root;
    std::string cores;
    std::string cache;
    std::string data;
    std::string state;
    std::string outside;
    std::string requestFile;
    PlayerConfig config;
    PlayerRequest request;
};

Fixture makeFixture() {
    Fixture f;
    f.root = makeTempDir();
    f.cores = f.root + "/cores";
    f.cache = f.root + "/cache";
    f.data = f.root + "/data";
    f.state = f.root + "/state";
    f.outside = f.root + "/outside";
    f.requestFile = f.state + "/journals/request.json";

    makeDirs(f.cores);
    makeDirs(f.cache + "/roms");
    makeDirs(f.data + "/firmware/system");
    makeDirs(f.data + "/saves/game");
    makeDirs(f.state + "/journals");
    makeDirs(f.outside);

    writeFile(f.cores + "/libtest_core.so", "fake core bytes");
    writeFile(f.cache + "/roms/test.rom", "fake rom bytes");
    writeFile(f.outside + "/evil.rom", "evil bytes");

    // Escape vectors: symlinked file, symlinked directory, absolute
    // symlink.
    makeSymlink("../outside/evil.rom", f.cache + "/link_outside");
    makeSymlink("../outside", f.cache + "/linkdir");
    makeSymlink("/etc/hosts", f.cores + "/link_etc");

    f.request.sessionId = "11111111-2222-3333-4444-555555555555";
    f.request.coreId = "testcore";
    f.request.coreBuildRevision = "rev-123";
    f.request.corePath = f.cores + "/libtest_core.so";
    f.request.contentPath = f.cache + "/roms/test.rom";
    f.request.contentHash = "deadbeef";
    f.request.systemDir = f.data + "/firmware/system";
    f.request.savePath = f.data + "/saves/game/autosave.srm";
    f.request.candidateSavePath = f.state + "/journals/candidate.srm";
    f.request.resultPath = f.state + "/journals/result.json";
    f.request.expectedSaveSize = 32768;
    f.request.video.fullscreen = true;

    f.config.roots.coreRoot = f.cores;
    f.config.roots.cacheRoot = f.cache;
    f.config.roots.dataRoot = f.data;
    f.config.roots.stateRoot = f.state;
    f.config.roots.allowedCores = {{"testcore", "rev-123"}};
    f.config.roots.expectedContentHash = "deadbeef";
    f.config.roots.sessionActive = [](const std::string&) { return false; };
    return f;
}

// Writes the request as a JSON file (mode 0644, current user) and
// validates it.
ValidationOutcome writeAndValidate(const Fixture& f,
                                   const PlayerRequest& request) {
    writeFile(f.requestFile, romm::player::serializeRequest(request));
    chmod(f.requestFile.c_str(), 0644);
    return validateRequestFile(f.requestFile, f.config);
}

void expectOk(const ValidationOutcome& outcome, const char* label) {
    if (!outcome.ok)
        std::fprintf(stderr, "expected ok (%s): %s\n", label,
                     outcome.error.c_str());
    CHECK(outcome.ok);
}

void expectRejected(const ValidationOutcome& outcome, const char* label) {
    if (outcome.ok) std::fprintf(stderr, "expected rejection (%s)\n", label);
    CHECK(!outcome.ok);
    CHECK(!outcome.error.empty());
}

}  // namespace

int main() {
    Fixture f = makeFixture();

    // --- Valid request acceptance ------------------------------------
    expectOk(writeAndValidate(f, f.request), "valid request");

    // Empty contentHash skips hash verification (legal in v1).
    {
        PlayerRequest r = f.request;
        r.contentHash = "";
        expectOk(writeAndValidate(f, r), "empty contentHash");
    }

    // --- Empty contentPath (no-content cores) ---------------------------
    // No-content cores (e.g. test_core) load with retro_load_game(nullptr);
    // the engine handles empty contentPath, and the core decides whether
    // no-content is acceptable — so validation must accept it.
    {
        PlayerRequest r = f.request;
        r.contentPath = "";
        r.contentHash = "";  // no game -> no hash
        expectOk(writeAndValidate(f, r), "empty contentPath (no-content core)");
    }

    // Non-empty contentPath outside cacheRoot is still rejected: the
    // containment check must not have been weakened.
    {
        PlayerRequest r = f.request;
        r.contentPath = f.outside + "/evil.rom";
        expectRejected(writeAndValidate(f, r), "contentPath outside cacheRoot");
    }

    // The other paths must remain non-empty.
    {
        PlayerRequest r = f.request;
        r.corePath = "";
        expectRejected(writeAndValidate(f, r), "empty corePath");
    }
    {
        PlayerRequest r = f.request;
        r.resultPath = "";
        expectRejected(writeAndValidate(f, r), "empty resultPath");
    }

    // Not-yet-existing paths under an approved root are fine (the
    // candidate save does not exist until the player writes it).
    {
        PlayerRequest r = f.request;
        r.candidateSavePath = f.state + "/journals/new/candidate.srm";
        r.resultPath = f.state + "/journals/new/result.json";
        expectOk(writeAndValidate(f, r), "nonexistent paths under root");
    }

    // `..` and `.` that stay inside the root canonicalize away.
    {
        PlayerRequest r = f.request;
        r.contentPath = f.cache + "/roms/../roms/./test.rom";
        expectOk(validateRequest(r, f.config), "in-root dotdot");
    }

    // Relative paths resolve against the working directory.
    {
        char cwd[PATH_MAX];
        if (getcwd(cwd, sizeof(cwd)) == nullptr) {
            std::fprintf(stderr, "fatal: getcwd\n");
            return 2;
        }
        if (chdir(f.root.c_str()) != 0) {
            std::fprintf(stderr, "fatal: chdir\n");
            return 2;
        }
        PlayerRequest r = f.request;
        r.contentPath = "cache/roms/test.rom";
        expectOk(validateRequest(r, f.config), "relative path in root");
        r.contentPath = "outside/evil.rom";
        expectRejected(validateRequest(r, f.config), "relative path escape");
        chdir(cwd);
    }

    // --- Path escapes --------------------------------------------------
    {
        PlayerRequest r = f.request;
        r.contentPath = f.cache + "/roms/../../outside/evil.rom";
        expectRejected(writeAndValidate(f, r), "dotdot traversal escape");
    }
    {
        PlayerRequest r = f.request;
        r.contentPath = "/etc/hosts";
        expectRejected(writeAndValidate(f, r), "absolute path escape");
    }
    {
        PlayerRequest r = f.request;
        r.corePath = f.cores + "/../outside/evil.rom";
        expectRejected(writeAndValidate(f, r), "corePath dotdot escape");
    }
    {
        PlayerRequest r = f.request;
        r.savePath = f.state + "/journals/wrong.srm";  // state, not data
        expectRejected(writeAndValidate(f, r), "savePath wrong root");
    }
    {
        PlayerRequest r = f.request;
        r.resultPath = f.data + "/saves/wrong.json";  // data, not state
        expectRejected(writeAndValidate(f, r), "resultPath wrong root");
    }
    {
        PlayerRequest r = f.request;
        r.systemDir = f.cache + "/roms";  // cache, not data
        expectRejected(writeAndValidate(f, r), "systemDir wrong root");
    }

    // --- Symlink rejection ---------------------------------------------
    {
        PlayerRequest r = f.request;
        r.contentPath = f.cache + "/link_outside";
        expectRejected(writeAndValidate(f, r), "symlinked file escape");
    }
    {
        PlayerRequest r = f.request;
        r.contentPath = f.cache + "/linkdir/evil.rom";
        expectRejected(writeAndValidate(f, r), "symlinked dir escape");
    }
    {
        PlayerRequest r = f.request;
        r.corePath = f.cores + "/link_etc";
        expectRejected(writeAndValidate(f, r), "absolute symlink core");
    }

    // --- Request file checks -------------------------------------------
    {
        writeFile(f.requestFile, romm::player::serializeRequest(f.request));
        chmod(f.requestFile.c_str(), 0666);  // world-writable
        expectRejected(validateRequestFile(f.requestFile, f.config),
                       "world-writable request file");
        chmod(f.requestFile.c_str(), 0644);
    }
    {
        writeFile(f.requestFile, romm::player::serializeRequest(f.request));
        chmod(f.requestFile.c_str(), 0644);
        std::string link = f.state + "/journals/request_link.json";
        makeSymlink(f.requestFile, link);
        expectRejected(validateRequestFile(link, f.config),
                       "symlinked request file");
        ::unlink(link.c_str());
    }
    {
        PlayerRequest r = f.request;
        r.protocolVersion = 2;
        expectRejected(writeAndValidate(f, r), "unknown protocol version");
    }
    {
        expectRejected(validateRequestFile(f.state + "/journals/nope.json",
                                           f.config),
                       "missing request file");
    }

    // --- Core allowlist --------------------------------------------------
    {
        PlayerRequest r = f.request;
        r.coreId = "evilcore";
        expectRejected(writeAndValidate(f, r), "core not in allowlist");
    }
    {
        PlayerRequest r = f.request;
        r.coreBuildRevision = "rogue-rev";
        expectRejected(writeAndValidate(f, r), "core revision mismatch");
    }

    // --- Content hash ------------------------------------------------------
    {
        PlayerRequest r = f.request;
        r.contentHash = "wrong-hash";
        expectRejected(writeAndValidate(f, r), "content hash mismatch");
    }

    // --- Session lock --------------------------------------------------------
    {
        Fixture locked = f;
        locked.config.roots.sessionActive =
            [](const std::string& id) { return id == "locked-session"; };
        PlayerRequest r = f.request;
        r.sessionId = "locked-session";
        expectRejected(validateRequest(r, locked.config), "active session");
        r.sessionId = "free-session";
        expectOk(validateRequest(r, locked.config), "free session");
    }

    // --- canonicalPath sanity -----------------------------------------------
    {
        std::string err;
        auto canon = canonicalPath(f.cache + "/roms/../roms/test.rom", &err);
        CHECK(canon.has_value());
        auto rootCanon = canonicalPath(f.root, &err);
        CHECK(rootCanon.has_value());
        if (canon && rootCanon)
            CHECK_EQ(*canon, *rootCanon + "/cache/roms/test.rom");

        // A symlink canonicalizes to its target.
        auto linkCanon = canonicalPath(f.cache + "/link_outside", &err);
        CHECK(linkCanon.has_value());
        auto outsideCanon = canonicalPath(f.outside + "/evil.rom", &err);
        CHECK(outsideCanon.has_value());
        if (linkCanon && outsideCanon) CHECK_EQ(*linkCanon, *outsideCanon);

        // Leading ".." cannot escape the root: /../etc/hosts must
        // canonicalize exactly like /etc/hosts (which itself may be a
        // symlink, e.g. to /private/etc/hosts on macOS).
        auto above = canonicalPath("/../etc/hosts", &err);
        auto etcCanon = canonicalPath("/etc/hosts", &err);
        CHECK(above.has_value());
        CHECK(etcCanon.has_value());
        if (above && etcCanon) CHECK_EQ(*above, *etcCanon);

        auto missing = canonicalPath("/definitely/not/here/x/y", &err);
        CHECK(missing.has_value());
        if (missing) CHECK_EQ(*missing, "/definitely/not/here/x/y");
    }

    // --- SessionId validation -----------------------------------------------
    // sessionId must be non-empty, <=64 chars, [A-Za-z0-9_-] only. This is
    // checked before sessionActive so a malformed sessionId never builds a
    // lock path.
    {
        PlayerRequest r = f.request;
        r.sessionId = "";
        expectRejected(writeAndValidate(f, r), "empty sessionId");
    }
    {
        PlayerRequest r = f.request;
        r.sessionId = "has space";
        expectRejected(writeAndValidate(f, r), "sessionId with space");
    }
    {
        PlayerRequest r = f.request;
        r.sessionId = "with/slash";
        expectRejected(writeAndValidate(f, r), "sessionId with slash");
    }
    {
        PlayerRequest r = f.request;
        r.sessionId = "../../tmp/x";
        expectRejected(writeAndValidate(f, r), "sessionId with dotdot");
    }
    {
        PlayerRequest r = f.request;
        r.sessionId = "a";  // 1 char is fine
        expectOk(writeAndValidate(f, r), "sessionId single char");
    }
    {
        PlayerRequest r = f.request;
        // Exactly 64 chars — boundary accept.
        r.sessionId = std::string(64, 'a');
        expectOk(writeAndValidate(f, r), "sessionId 64 chars");
    }
    {
        PlayerRequest r = f.request;
        // 65 chars — boundary reject.
        r.sessionId = std::string(65, 'a');
        expectRejected(writeAndValidate(f, r), "sessionId 65 chars");
    }
    {
        PlayerRequest r = f.request;
        r.sessionId = "uuid-with-dashes-1234-5678";
        expectOk(writeAndValidate(f, r), "sessionId valid uuid-like");
    }
    {
        PlayerRequest r = f.request;
        r.sessionId = "has_underscore_ok";
        expectOk(writeAndValidate(f, r), "sessionId with underscore");
    }
    {
        PlayerRequest r = f.request;
        // Unicode (multi-byte UTF-8) must be rejected.
        r.sessionId = "\xc3\xa9t\xc3\xa9";  // "été" in UTF-8
        expectRejected(writeAndValidate(f, r), "sessionId with unicode");
    }
    {
        PlayerRequest r = f.request;
        r.sessionId = "has@at-sign";
        expectRejected(writeAndValidate(f, r), "sessionId with @");
    }
    {
        PlayerRequest r = f.request;
        r.sessionId = "has;semicolon";
        expectRejected(writeAndValidate(f, r), "sessionId with semicolon");
    }

    return rommtest::finish("test_player_validation");
}
