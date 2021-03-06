import edu.nyu.aquery.analysis.AnalysisTypes._
import edu.nyu.aquery.analysis.TypeChecker
import edu.nyu.aquery.ast.{InnerJoinOn, Join, Table}
import edu.nyu.aquery.parse.AqueryParser
import edu.nyu.aquery.parse.AqueryParser._

import org.scalatest.FunSuite

import scala.io.Source


class TypeCheckerTestSuite extends FunSuite {
  def typeCheckResource(resource: String): Boolean = {
    val prog = Source.fromURL(getClass.getResource(resource)).getLines.mkString("\n")
    val parsed = AqueryParser(prog)
    parsed.map { prog => TypeChecker(prog).typeCheck(prog).isEmpty }.getOrElse(false)
  }

  // simple type checker with only built-in function information
  val simpleChecker = TypeChecker()

  // check binary expressions
  test("expressions") {
    // simple arithmetic
    val badDiv = parse(expr, "2 * 3 / \"a\"").get
    assert(simpleChecker.checkExpr(badDiv)._2.nonEmpty, "cannot divide by string")

    val okExp = parse(expr, "x ^ true").get
    assert(simpleChecker.checkExpr(okExp) === (TNumeric, Seq()), "can exponentiate with boolean")

    val okUnk = parse(expr, "x + y / z").get
    assert(simpleChecker.checkExpr(okUnk) === (TNumeric, Seq()), "can always handle unknowns")

    val okNeg = parse(expr, "-x").get
    assert(simpleChecker.checkExpr(okNeg) === (TNumeric, Seq()), "negation")

    val badNeg = parse(expr, "-false").get
    assert(simpleChecker.checkExpr(badNeg) match {
      case (TNumeric, List(err)) => true; case _ => false
    }, "bad input but assumes type on exit")

    // case expressions
    val okCase = parse(expr,
      """
        CASE f(c1)
          WHEN TRUE THEN 1
          ELSE 2
         END
      """).get
    assert(simpleChecker.checkExpr(okCase) === (TNumeric, List()), "branches agree")

    val badCase1 = parse(expr,
      """
        CASE f(c1)
          WHEN TRUE THEN 1
          ELSE TRUE
         END
      """).get
    assert(simpleChecker.checkExpr(badCase1)._2.nonEmpty, "branches disagree")

    val badCase2 = parse(expr,
      """
        CASE f(c1)
          WHEN TRUE THEN
            CASE
              WHEN c1 > 2 THEN 1
              ELSE 2
            END
          ELSE TRUE
         END
      """).get
    assert(simpleChecker.checkExpr(badCase2)._2.nonEmpty, "branches disagree in nested case")

    val badCase3 = parse(expr,
      """
        CASE max(c2) * 1
          WHEN "a" THEN 1
        END
      """).get
    assert(simpleChecker.checkExpr(badCase3)._2.nonEmpty, "bad type in when case")

    // built-in function calls
    val okBuiltIn = parse(expr, "fills(max(c1) * 1)").get
    assert(simpleChecker.checkExpr(okBuiltIn) === (TNumeric, List()), "fill on numeric -> numeric")

    val badBuiltIn = parse(expr, "fills(\"true\", avgs(c1))").get
    assert(simpleChecker.checkExpr(badBuiltIn)._2.nonEmpty, "tried filling with wrong type")

    val badBuiltIn2 = parse(expr, "sums(2, 3, c2)").get
    assert(simpleChecker.checkExpr(badBuiltIn2)._2.nonEmpty, "wrong number of args")
  }

  // UDFs are simply checked for number of arguments
  // which are collected in a first pass that creates the type checker
  // UDF definitions check the body for errors
  test("udf") {
    val f = parse(udf, "FUNCTION f(x, y) {2}").get
    val g = parse(udf, "FUNCTION g(x) {2}").get
    val h = parse(udf, "FUNCTION h() {2}").get
    val goodCalls = List("f(1, 2)", "g(1)", "h()").map(parse(expr, _).get)
    val badCalls = List("f(1, 2, 3)", "g()", "h(1)").map(parse(expr, _).get)

    val checker = TypeChecker(List(f, g, h))
    assert(goodCalls.map(checker.checkExpr).forall(_._2.isEmpty), "all good calls work")
    assert(badCalls.map(checker.checkExpr).forall(_._2.nonEmpty), "all bad calls fail")

    val def1 = parse(udf, "FUNCTION f(x, y) { maxs(\"a\") }").get
    assert(checker.checkUDF(def1).nonEmpty, "bad use of maxs in body")

    val def2 = parse(udf, "FUNCTION f(x, y) { x * y + 2 }").get
    assert(checker.checkTopLevel(def2).isEmpty, "body ok")
  }

  // we skip positive examples here as the benchmarks are more than enough to cover
  // reasonable cases
  test("queries") {
    // filter
    val badFilter = parse(fullQuery, "SELECT * FROM t where c2 > 3 AND maxs(c1) * 2").get
    assert(simpleChecker.checkQuery(badFilter).nonEmpty, "bad numeric filter")
    // having
    val badHaving = parse(fullQuery, "SELECT sum(c2) FROM t GROUP BY c3 HAVING sums(c4)").get
    assert(simpleChecker.checkQuery(badHaving).nonEmpty, "bad numeric having")
    // join
    val badJoin = Join(InnerJoinOn,Table("c1"), Table("c2"), parse(expr, "avg(c1)").get :: Nil)
    assert(simpleChecker.checkRelAlg(badJoin).nonEmpty, "bad numeric join condition")
    // issues in project
    val badProject = parse(fullQuery, "SELECT c1 * 2 /\"a\" as bad FROM t WHERE c1 = 2").get
    assert(simpleChecker.checkQuery(badProject).nonEmpty, "type error in projection")
    // issues in local query rather than main
    val badLocal = parse(fullQuery,
      """
         WITH
          t1 AS (SELECT * FROM t WHERE avg(c1))
          SELECT * FROM t1
      """
    ).get
    assert(simpleChecker.checkQuery(badLocal).nonEmpty, "bad local query")

    // correlation names etc
    val dupeTables = parse(fullQuery, "SELECT * FROM t1, t2 INNER JOIN t1 USING c1").get
    assert(simpleChecker.checkQuery(dupeTables).nonEmpty, "duplicate tables")

    val dupeCorrNames = parse(fullQuery, "SELECT * FROM t1, t2 bad, t3 as bad").get
    assert(simpleChecker.checkQuery(dupeCorrNames).nonEmpty, "duplicate alias")

    val okTables = parse(fullQuery,
      "SELECT * FROM t1 as firstT1, t2 INNER JOIN t2 other_t2 USING cx"
    ).get
    assert(simpleChecker.checkQuery(okTables) === List())

    val badColAccess = parse(fullQuery,
      "SELECT bad.c1, some_t.c2 * st.ok, f(c3 * 2) FROM some_t st"
    ).get
    assert(simpleChecker.checkQuery(badColAccess).length === 2, "bad col access")

    val ambigColAccess = parse(fullQuery,
      "SELECT t.c1 * t.c2 FROM t as first_t, t second_t"
    ).get
    assert(simpleChecker.checkQuery(ambigColAccess).nonEmpty, "ambig col access")

    val ambigColAccess2 = parse(fullQuery,
      "SELECT t.c1 FROM t first_t WHERE c1 > 2"
    ).get
    assert(simpleChecker.checkQuery(ambigColAccess2).nonEmpty, "ambig col access")

    val nonAmbigColAccess = parse(fullQuery,
      "SELECT first_t.c1 * second_t.c2 FROM t as first_t, t second_t"
    ).get
    assert(simpleChecker.checkQuery(nonAmbigColAccess) === List(), "non-ambig col access")

    val badSort = parse(fullQuery, "SELECT * FROM t ASSUMING ASC c2 * 2").get
    assert(simpleChecker.checkQuery(badSort).nonEmpty, "bad sorting spec")

    val okSort = parse(fullQuery, "SELECT * FROM t ASSUMING ASC c2, DESC t.c2").get
    assert(simpleChecker.checkQuery(okSort) === List())
  }

  // just do update, delete is effectively the same type checking
  test("modification queries") {
    val badUpdate = parse(update, "UPDATE t SET c1 = bad.c2 * 3 WHERE c1 > 2").get
    assert(simpleChecker.checkModificationQuery(badUpdate).nonEmpty, "bad col access")
    val okUpdate = parse(update, "UPDATE t ASSUMING ASC c2 SET c1 = f(t.c1) WHERE sums(c1 > 2) > 2").get
    assert(simpleChecker.checkModificationQuery(okUpdate) === List())
  }

  // full programs
  // use most/all constructs
  test("simple program") {
    assert(typeCheckResource("simple.a"), "simple program code parses")
  }

  test("fintime benchmark") {
    assert(typeCheckResource("fintime.a"), "fintime benchmark code parses")
  }

  test("monetbd benchmark") {
    assert(typeCheckResource("monetdb.a"), "monetdb benchmark code parses")
  }

  test("pandas benchmark") {
    assert(typeCheckResource("pandas.a"), "pandas benchmark code parses")
  }
}
