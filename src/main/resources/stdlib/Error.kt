package ktc.std

fun error(message: String): Nothing {
    c.ktc_core_stacktrace_print(message.ptr, message.len);
    c.exit(c.EXIT_FAILURE);
}