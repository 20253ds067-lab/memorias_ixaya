import os
import sqlite3
import uuid
from datetime import datetime
from flask import Flask, request, jsonify, send_from_directory
from werkzeug.security import generate_password_hash, check_password_hash
from werkzeug.utils import secure_filename

app = Flask(__name__)

# Configuración
BASE_DIR = os.path.abspath(os.path.dirname(__file__))
DB_PATH = os.path.join(BASE_DIR, "travel_album.db")
UPLOAD_FOLDER = os.path.join(BASE_DIR, "uploads")
os.makedirs(UPLOAD_FOLDER, exist_ok=True)

app.config["UPLOAD_FOLDER"] = UPLOAD_FOLDER
ALLOWED_EXTENSIONS = {"png", "jpg", "jpeg", "webp"}


def get_db():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    return conn


def init_db():
    """Inicializa las tablas en SQLite si no existen."""
    with get_db() as conn:
        conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS users (
                matricula TEXT PRIMARY KEY,
                nombres TEXT NOT NULL,
                apellidos TEXT NOT NULL,
                password_hash TEXT NOT NULL,
                created_at TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS memories (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_matricula TEXT NOT NULL,
                titulo TEXT NOT NULL,
                lugar TEXT NOT NULL,
                latitud REAL NOT NULL,
                longitud REAL NOT NULL,
                descripcion TEXT NOT NULL,
                image_filename TEXT,
                timestamp TEXT NOT NULL,
                is_deleted INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY (user_matricula) REFERENCES users(matricula)
            );

            CREATE TABLE IF NOT EXISTS likes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                memory_id INTEGER NOT NULL,
                liker_matricula TEXT NOT NULL,
                created_at TEXT NOT NULL,
                notified INTEGER NOT NULL DEFAULT 0,
                UNIQUE(memory_id, liker_matricula),
                FOREIGN KEY (memory_id) REFERENCES memories(id),
                FOREIGN KEY (liker_matricula) REFERENCES users(matricula)
            );

            CREATE TABLE IF NOT EXISTS edit_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                memory_id INTEGER NOT NULL,
                edited_at TEXT NOT NULL,
                change_summary TEXT NOT NULL,
                FOREIGN KEY (memory_id) REFERENCES memories(id)
            );
            """
        )


def allowed_file(filename):
    return "." in filename and filename.rsplit(".", 1)[1].lower() in ALLOWED_EXTENSIONS


def format_memory_row(conn, row, current_user_matricula=None):
    """Serializa un recuerdo incluyendo likes, estado de like del usuario e historial."""
    memory_id = row["id"]

    # Conteo total de likes
    likes_count = conn.execute(
        "SELECT COUNT(*) as total FROM likes WHERE memory_id = ?", (memory_id,)
    ).fetchone()["total"]

    # Saber si el usuario actual ya dio like
    is_liked = False
    if current_user_matricula:
        liked_row = conn.execute(
            "SELECT 1 FROM likes WHERE memory_id = ? AND liker_matricula = ?",
            (memory_id, current_user_matricula),
        ).fetchone()
        is_liked = liked_row is not None

    # Historial de cambios ordenado del más reciente al más antiguo
    history_rows = conn.execute(
        "SELECT id, edited_at, change_summary FROM edit_history WHERE memory_id = ? ORDER BY id DESC",
        (memory_id,),
    ).fetchall()
    history = [
        {
            "id": h["id"],
            "edited_at": h["edited_at"],
            "change_summary": h["change_summary"],
        }
        for h in history_rows
    ]

    image_url = None
    if row["image_filename"]:
        image_url = f"{request.host_url.rstrip('/')}/uploads/{row['image_filename']}"

    return {
        "id": memory_id,
        "user_matricula": row["user_matricula"],
        "author_name": f"{row['nombres']} {row['apellidos']}",
        "titulo": row["titulo"],
        "lugar": row["lugar"],
        "latitud": row["latitud"],
        "longitud": row["longitud"],
        "descripcion": row["descripcion"],
        "image_filename": row["image_filename"],
        "image_url": image_url,
        "calificacion_likes": likes_count,
        "is_liked_by_me": is_liked,
        "timestamp": row["timestamp"],
        "is_deleted": int(row["is_deleted"]),
        "edit_history": history,
    }


# ==========================================
# ENDPOINTS DE AUTENTICACIÓN
# ==========================================


@app.route("/api/auth/register", methods=["POST"])
def register():
    data = request.get_json(silent=True) or {}
    nombres = (data.get("nombres") or "").strip()
    apellidos = (data.get("apellidos") or "").strip()
    matricula = (data.get("matricula") or "").strip()
    password = data.get("password") or ""

    if not all([nombres, apellidos, matricula, password]):
        return jsonify({"error": "Todos los campos son obligatorios"}), 400

    with get_db() as conn:
        existing = conn.execute(
            "SELECT matricula FROM users WHERE matricula = ?", (matricula,)
        ).fetchone()
        if existing:
            return jsonify({"error": "La matrícula ya está registrada"}), 409

        now = datetime.now().isoformat()
        conn.execute(
            "INSERT INTO users (matricula, nombres, apellidos, password_hash, created_at) VALUES (?, ?, ?, ?, ?)",
            (matricula, nombres, apellidos, generate_password_hash(password), now),
        )

    return (
        jsonify(
            {
                "success": True,
                "message": "Usuario registrado exitosamente",
                "user": {
                    "matricula": matricula,
                    "nombres": nombres,
                    "apellidos": apellidos,
                },
            }
        ),
        201,
    )


@app.route("/api/auth/login", methods=["POST"])
def login():
    data = request.get_json(silent=True) or {}
    matricula = (data.get("matricula") or "").strip()
    password = data.get("password") or ""

    if not matricula or not password:
        return jsonify({"error": "Ingresa matrícula y contraseña"}), 400

    with get_db() as conn:
        user = conn.execute(
            "SELECT * FROM users WHERE matricula = ?", (matricula,)
        ).fetchone()

        if not user or not check_password_hash(user["password_hash"], password):
            return jsonify({"error": "Matrícula o contraseña incorrectos"}), 401

        return (
            jsonify(
                {
                    "success": True,
                    "message": "Inicio de sesión exitoso",
                    "user": {
                        "matricula": user["matricula"],
                        "nombres": user["nombres"],
                        "apellidos": user["apellidos"],
                    },
                }
            ),
            200,
        )


# ==========================================
# ENDPOINTS DE RECUERDOS (CRUD + OFFLINE SYNC)
# ==========================================


@app.route("/api/memories", methods=["GET"])
def get_memories():
    """
    Obtiene todos los recuerdos (incluidos los eliminados lógicamente).
    Query params opcionales:
      - current_user: matrícula del usuario autenticado (para saber si dio like)
      - owner_matricula: filtra únicamente los recuerdos de un usuario ("Mi Álbum")
    """
    current_user = request.args.get("current_user")
    owner_filter = request.args.get("owner_matricula")

    query = """
        SELECT m.*, u.nombres, u.apellidos
        FROM memories m
        JOIN users u ON m.user_matricula = u.matricula
    """
    params = []
    if owner_filter:
        query += " WHERE m.user_matricula = ?"
        params.append(owner_filter)

    query += " ORDER BY m.timestamp DESC, m.id DESC"

    with get_db() as conn:
        rows = conn.execute(query, params).fetchall()
        result = [format_memory_row(conn, r, current_user) for r in rows]

    return jsonify(result), 200


@app.route("/api/memories", methods=["POST"])
def create_memory():
    """
    Crea un nuevo recuerdo (soporta multipart/form-data para foto + datos).
    Acepta 'timestamp' enviado desde Android para respetar el momento exacto
    en que se tomó la foto sin señal antes de sincronizar.
    """
    user_matricula = (request.form.get("user_matricula") or "").strip()
    titulo = (request.form.get("titulo") or "").strip()
    lugar = (request.form.get("lugar") or "").strip()
    descripcion = (request.form.get("descripcion") or "").strip()
    latitud = request.form.get("latitud", type=float) or 0.0
    longitud = request.form.get("longitud", type=float) or 0.0
    timestamp = (request.form.get("timestamp") or "").strip() or datetime.now().isoformat()

    if not all([user_matricula, titulo, lugar, descripcion]):
        return jsonify({"error": "Faltan campos obligatorios del recuerdo"}), 400

    image_filename = None
    if "image" in request.files:
        file = request.files["image"]
        if file and file.filename and allowed_file(file.filename):
            ext = file.filename.rsplit(".", 1)[1].lower()
            unique_name = f"{uuid.uuid4().hex}_{secure_filename(file.filename)}"
            if not unique_name.endswith(f".{ext}"):
                unique_name = f"{unique_name}.{ext}"
            file.save(os.path.join(app.config["UPLOAD_FOLDER"], unique_name))
            image_filename = unique_name

    with get_db() as conn:
        user = conn.execute(
            "SELECT matricula FROM users WHERE matricula = ?", (user_matricula,)
        ).fetchone()
        if not user:
            return jsonify({"error": "El usuario no existe"}), 404

        cursor = conn.execute(
            """
            INSERT INTO memories
            (user_matricula, titulo, lugar, latitud, longitud, descripcion, image_filename, timestamp, is_deleted)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)
            """,
            (
                user_matricula,
                titulo,
                lugar,
                latitud,
                longitud,
                descripcion,
                image_filename,
                timestamp,
            ),
        )
        new_id = cursor.lastrowid
        row = conn.execute(
            """
            SELECT m.*, u.nombres, u.apellidos
            FROM memories m
            JOIN users u ON m.user_matricula = u.matricula
            WHERE m.id = ?
            """,
            (new_id,),
        ).fetchone()
        created_memory = format_memory_row(conn, row, user_matricula)

    return jsonify(created_memory), 201


@app.route("/api/memories/<int:memory_id>", methods=["PUT"])
def update_memory(memory_id):
    """Edita un recuerdo (solo el dueño) y registra el cambio en edit_history."""
    data = request.get_json(silent=True) or {}
    requester_matricula = (data.get("user_matricula") or request.args.get("user_matricula") or "").strip()
    new_titulo = (data.get("titulo") or "").strip()
    new_lugar = (data.get("lugar") or "").strip()
    new_descripcion = (data.get("descripcion") or "").strip()

    with get_db() as conn:
        memory = conn.execute(
            "SELECT * FROM memories WHERE id = ?", (memory_id,)
        ).fetchone()
        if not memory:
            return jsonify({"error": "Recuerdo no encontrado"}), 404
        if memory["is_deleted"]:
            return jsonify({"error": "No se puede editar un recuerdo eliminado"}), 400
        if memory["user_matricula"] != requester_matricula:
            return jsonify({"error": "Solo el dueño puede editar este recuerdo"}), 403

        # Detectar qué campos cambiaron para el historial
        cambios = []
        if new_titulo and new_titulo != memory["titulo"]:
            cambios.append(f"Título actualizado a '{new_titulo}'")
        else:
            new_titulo = memory["titulo"]

        if new_lugar and new_lugar != memory["lugar"]:
            cambios.append(f"Lugar actualizado a '{new_lugar}'")
        else:
            new_lugar = memory["lugar"]

        if new_descripcion and new_descripcion != memory["descripcion"]:
            cambios.append("Descripción modificada")
        else:
            new_descripcion = memory["descripcion"]

        if not cambios:
            return jsonify({"message": "Sin cambios detectados"}), 200

        now = datetime.now().isoformat()
        conn.execute(
            """
            UPDATE memories
            SET titulo = ?, lugar = ?, descripcion = ?
            WHERE id = ?
            """,
            (new_titulo, new_lugar, new_descripcion, memory_id),
        )
        conn.execute(
            """
            INSERT INTO edit_history (memory_id, edited_at, change_summary)
            VALUES (?, ?, ?)
            """,
            (memory_id, now, " | ".join(cambios)),
        )

        updated_row = conn.execute(
            """
            SELECT m.*, u.nombres, u.apellidos
            FROM memories m
            JOIN users u ON m.user_matricula = u.matricula
            WHERE m.id = ?
            """,
            (memory_id,),
        ).fetchone()
        return jsonify(format_memory_row(conn, updated_row, requester_matricula)), 200


@app.route("/api/memories/<int:memory_id>", methods=["DELETE"])
def delete_memory(memory_id):
    """Eliminación lógica (solo el dueño). Marca is_deleted = 1."""
    requester_matricula = (
        request.args.get("user_matricula")
        or (request.get_json(silent=True) or {}).get("user_matricula")
        or ""
    ).strip()

    with get_db() as conn:
        memory = conn.execute(
            "SELECT * FROM memories WHERE id = ?", (memory_id,)
        ).fetchone()
        if not memory:
            return jsonify({"error": "Recuerdo no encontrado"}), 404
        if memory["user_matricula"] != requester_matricula:
            return jsonify({"error": "Solo el dueño puede eliminar este recuerdo"}), 403

        conn.execute(
            "UPDATE memories SET is_deleted = 1 WHERE id = ?", (memory_id,)
        )

    return jsonify({"message": "Recuerdo eliminado lógicamente", "id": memory_id}), 200


@app.route("/api/memories/<int:memory_id>/history", methods=["GET"])
def get_memory_history(memory_id):
    """Obtiene el historial de ediciones de un recuerdo específico."""
    with get_db() as conn:
        memory = conn.execute(
            "SELECT id FROM memories WHERE id = ?", (memory_id,)
        ).fetchone()
        if not memory:
            return jsonify({"error": "Recuerdo no encontrado"}), 404

        rows = conn.execute(
            """
            SELECT id, edited_at, change_summary
            FROM edit_history
            WHERE memory_id = ?
            ORDER BY id DESC
            """,
            (memory_id,),
        ).fetchall()

        history = [
            {
                "id": r["id"],
                "edited_at": r["edited_at"],
                "change_summary": r["change_summary"],
            }
            for r in rows
        ]

    return jsonify(history), 200


# ==========================================
# ENDPOINTS DE LIKES Y NOTIFICACIONES PUSH
# ==========================================


@app.route("/api/memories/<int:memory_id>/like", methods=["POST"])
def toggle_like(memory_id):
    """Alterna el like de un usuario sobre un recuerdo."""
    data = request.get_json(silent=True) or {}
    liker_matricula = (data.get("liker_matricula") or "").strip()

    if not liker_matricula:
        return jsonify({"error": "Matrícula requerida"}), 400

    with get_db() as conn:
        memory = conn.execute(
            "SELECT * FROM memories WHERE id = ?", (memory_id,)
        ).fetchone()
        if not memory:
            return jsonify({"error": "Recuerdo no encontrado"}), 404
        if memory["is_deleted"]:
            return jsonify({"error": "No se puede dar like a un recuerdo eliminado"}), 400

        existing_like = conn.execute(
            "SELECT id FROM likes WHERE memory_id = ? AND liker_matricula = ?",
            (memory_id, liker_matricula),
        ).fetchone()

        if existing_like:
            conn.execute("DELETE FROM likes WHERE id = ?", (existing_like["id"],))
            action = "unliked"
        else:
            # Si el dueño se da autolike, lo marcamos ya notificado para no enviarse notificación a sí mismo
            already_notified = 1 if memory["user_matricula"] == liker_matricula else 0
            conn.execute(
                """
                INSERT INTO likes (memory_id, liker_matricula, created_at, notified)
                VALUES (?, ?, ?, ?)
                """,
                (memory_id, liker_matricula, datetime.now().isoformat(), already_notified),
            )
            action = "liked"

        total_likes = conn.execute(
            "SELECT COUNT(*) as total FROM likes WHERE memory_id = ?", (memory_id,)
        ).fetchone()["total"]

    return (
        jsonify(
            {
                "memory_id": memory_id,
                "action": action,
                "calificacion_likes": total_likes,
            }
        ),
        200,
    )


@app.route("/api/notifications/pending/<matricula>", methods=["GET"])
def get_pending_like_notifications(matricula):
    """
    Endpoint consumido por WorkManager en Android (incluso con la app cerrada).
    Devuelve los likes nuevos en fotos del usuario y los marca como notificados.
    """
    with get_db() as conn:
        rows = conn.execute(
            """
            SELECT l.id as like_id,
                   l.liker_matricula,
                   l.created_at,
                   m.id as memory_id,
                   m.titulo as memory_titulo,
                   u.nombres || ' ' || u.apellidos as liker_name
            FROM likes l
            JOIN memories m ON l.memory_id = m.id
            JOIN users u ON l.liker_matricula = u.matricula
            WHERE m.user_matricula = ?
              AND l.notified = 0
              AND m.is_deleted = 0
            ORDER BY l.id ASC
            """,
            (matricula,),
        ).fetchall()

        notifications = []
        like_ids = []
        for r in rows:
            like_ids.append(r["like_id"])
            notifications.append(
                {
                    "id": r["like_id"],
                    "memory_id": r["memory_id"],
                    "memory_titulo": r["memory_titulo"],
                    "liker_matricula": r["liker_matricula"],
                    "liker_name": r["liker_name"],
                    "created_at": r["created_at"],
                    "message": f"A {r['liker_name']} le gustó tu recuerdo \"{r['memory_titulo']}\"",
                }
            )

        if like_ids:
            placeholders = ",".join("?" for _ in like_ids)
            conn.execute(
                f"UPDATE likes SET notified = 1 WHERE id IN ({placeholders})",
                like_ids,
            )

    return jsonify(notifications), 200


# ==========================================
# SERVICIO DE IMÁGENES ESTÁTICAS
# ==========================================


@app.route("/uploads/<path:filename>", methods=["GET"])
def serve_upload(filename):
    return send_from_directory(app.config["UPLOAD_FOLDER"], filename)


if __name__ == "__main__":
    init_db()
    # host="0.0.0.0" permite conexiones desde el emulador (10.0.2.2) y teléfonos físicos en la misma red Wi-Fi
    print(app.url_map)
    app.run(host="0.0.0.0", port=5000, debug=True)
