package edu.nyu.aquery.ast

/**
 * Creates a DOT representation of the AST. Mainly useful for debugging and visualizing query
 * plans before/after optimizations
 */

object Dot {
  /**
   * Declares a node by assigning it identifier `id` and labeling with `label`
   * @param id
   * @param label
   * @return
   */
  def declareNode(id: Int, label: String) = s"""$id [label="$label"];\n"""

  /**
   * Declares an edge between two nodes
   * @param src
   * @param dest
   * @return
   */
  def declareEdge(src: Int, dest: Int) = s"$src -> $dest;\n"

  /**
   * Helper to correctly graph lists of a given type, feeds forward the node identifier
   * @param l list of elements to graph
   * @param currAvail current available node idenitifier number
   * @param fn function that generates all necessary DOT code for a given element, returns
   *           the code and the next available identifier
   * @tparam T extends AST so that we can call dotify on it
   * @return
   */
  protected[ast] def strList[T](l: List[T], currAvail: Int, fn: (T, Int) => (String, Int))
    : (String, Int) = {
    l.foldLeft(("", currAvail)) { case ((str, id), e) =>
        val (addStr, newId) = fn(e, id)
        (str + addStr, newId)
    }
  }

  /**
   * DOT graphviz format representation of program, can be written out now and plotted using
   * `dot <file> -Tpdf -o <output>.pdf` or a similar call
   * @param prog
   * @return
   */
  def toGraph(prog: AST[_]): String = {
    val entryNode = declareNode(0, "AQuery Entry")
    val entryEdge = declareEdge(0, 1)
    val (progGraph, _) = prog.dotify(1)
    "digraph G {\n" + entryNode + entryEdge + progGraph + "}\n"
  }
}
