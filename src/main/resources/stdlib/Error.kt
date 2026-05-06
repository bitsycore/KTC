package ktc.std

fun error(message: String): Nothing {
    c.fprintf(c.stderr, "%.*s\n", message.len, message.ptr);
    c.exit(c.EXIT_FAILURE);
}