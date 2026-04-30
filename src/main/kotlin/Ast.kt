package com.bitsycore

// ═══════════════════════════ Type Reference ═══════════════════════════

data class TypeRef(
    val name: String,
    val nullable: Boolean = false,
    val typeArgs: List<TypeRef> = emptyList()
)

// ═══════════════════════════ File ═══════════════════════════

data class KtFile(
    val pkg: String?,
    val imports: List<String>,
    val decls: List<Decl>
)

// ═══════════════════════════ Declarations ═══════════════════════════

sealed class Decl

data class FunDecl(
    val name: String,
    val params: List<Param>,
    val returnType: TypeRef?,
    val body: Block?,
    val receiver: TypeRef? = null
) : Decl()

data class ClassDecl(
    val name: String,
    val isData: Boolean,
    val ctorParams: List<CtorParam>,
    val members: List<Decl>,
    val initBlocks: List<Block>
) : Decl()

data class EnumDecl(
    val name: String,
    val entries: List<String>
) : Decl()

data class ObjectDecl(
    val name: String,
    val members: List<Decl>
) : Decl()

data class PropDecl(
    val name: String,
    val type: TypeRef?,
    val init: Expr?,
    val mutable: Boolean
) : Decl()

// ═══════════════════════════ Parameters ═══════════════════════════

data class Param(
    val name: String,
    val type: TypeRef,
    val default: Expr? = null
)

data class CtorParam(
    val name: String,
    val type: TypeRef,
    val default: Expr? = null,
    val isVal: Boolean = false,
    val isVar: Boolean = false
)

// ═══════════════════════════ Block ═══════════════════════════

data class Block(val stmts: List<Stmt>)

// ═══════════════════════════ Statements ═══════════════════════════

sealed class Stmt

data class ExprStmt(val expr: Expr) : Stmt()

data class VarDeclStmt(
    val name: String,
    val type: TypeRef?,
    val init: Expr?,
    val mutable: Boolean
) : Stmt()

data class AssignStmt(
    val target: Expr,
    val op: String,        // = += -= *= /= %=
    val value: Expr
) : Stmt()

data class ReturnStmt(val value: Expr?) : Stmt()
data class ForStmt(val varName: String, val iter: Expr, val body: Block) : Stmt()
data class WhileStmt(val cond: Expr, val body: Block) : Stmt()
data class DoWhileStmt(val body: Block, val cond: Expr) : Stmt()
object BreakStmt : Stmt()
object ContinueStmt : Stmt()

// ═══════════════════════════ Expressions ═══════════════════════════

sealed class Expr

data class IntLit(val value: Long) : Expr()
data class LongLit(val value: Long) : Expr()
data class DoubleLit(val value: Double) : Expr()
data class FloatLit(val value: Double) : Expr()
data class BoolLit(val value: Boolean) : Expr()
data class CharLit(val value: Char) : Expr()
data class StrLit(val value: String) : Expr()
data class StrTemplateExpr(val parts: List<StrPart>) : Expr()
object NullLit : Expr()
data class NameExpr(val name: String) : Expr()
object ThisExpr : Expr()

data class BinExpr(val left: Expr, val op: String, val right: Expr) : Expr()
data class PrefixExpr(val op: String, val expr: Expr) : Expr()
data class PostfixExpr(val expr: Expr, val op: String) : Expr()

data class CallExpr(val callee: Expr, val args: List<Arg>, val typeArgs: List<TypeRef> = emptyList()) : Expr()
data class DotExpr(val obj: Expr, val name: String) : Expr()
data class SafeDotExpr(val obj: Expr, val name: String) : Expr()
data class IndexExpr(val obj: Expr, val index: Expr) : Expr()

data class IfExpr(val cond: Expr, val then: Block, val els: Block?) : Expr()
data class WhenExpr(val subject: Expr?, val branches: List<WhenBranch>) : Expr()

data class NotNullExpr(val expr: Expr) : Expr()   // !!
data class ElvisExpr(val left: Expr, val right: Expr) : Expr()   // ?:
data class IsCheckExpr(val expr: Expr, val type: TypeRef, val negated: Boolean) : Expr()
data class CastExpr(val expr: Expr, val type: TypeRef) : Expr()

// ═══════════════════════════ Helpers ═══════════════════════════

data class Arg(val name: String? = null, val expr: Expr)

sealed class StrPart
data class LitPart(val text: String) : StrPart()
data class ExprPart(val expr: Expr) : StrPart()

data class WhenBranch(val conds: List<WhenCond>?, val body: Block)   // conds==null → else

sealed class WhenCond
data class ExprCond(val expr: Expr) : WhenCond()
data class IsCond(val type: TypeRef, val negated: Boolean = false) : WhenCond()
data class InCond(val expr: Expr, val negated: Boolean = false) : WhenCond()
