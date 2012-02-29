// author: jonathan bachrach
package Chisel {

import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.Queue
import scala.collection.mutable.Stack
import scala.collection.mutable.HashSet
import scala.collection.mutable.HashMap
import java.lang.reflect.Modifier._;
import java.io.File;

import scala.math.max;
import Node._;
import Component._;
import Bundle._;
import IOdir._;
import ChiselError._;

object Component {
  var saveWidthWarnings = false
  var saveConnectionWarnings = false
  var saveComponentTrace = false
  var findCombLoop = false
  var widthWriter: java.io.FileWriter = null
  var connWriter: java.io.FileWriter = null
  var isDebug = false;
  var isVCD = false;
  var isFolding = false;
  var isGenHarness = false;
  var isReportDims = false;
  var scanFormat = "";
  var scanArgs: Seq[Node] = null;
  var printFormat = "";
  var printArgs: ArrayBuffer[Node] = null;
  var includeArgs: List[String] = Nil;
  var targetEmulatorRootDir: String = null;
  var targetVerilogRootDir: String = null;
  var targetDir: String = null;
  var configStr: String = null;
  var compIndex = -1;
  val compIndices = HashMap.empty[String,Int];
  val compDefs = new HashMap[String, String];
  var isEmittingComponents = false;
  var isEmittingC = false;
  var topComponent: Component = null;
  val components = ArrayBuffer[Component]();
  val procs = ArrayBuffer[proc]();
  val resetList = ArrayBuffer[Node]();
  val muxes = ArrayBuffer[Node]();
  var ioMap = Map[Node, Int]();
  var ioCount = 0;
  val compStack = new Stack[Component]();
  var stackIndent = 0;
  var printStackStruct = ArrayBuffer[(Int, Component)]();
  var firstComp = true;
  var genCount = 0;
  def genCompName(name: String): String = {
    genCount += 1;
    name + "_" + genCount
  }
  def nextCompIndex : Int = { compIndex = compIndex + 1; compIndex }
  def splitArg (s: String) = s.split(' ').toList;
  // TODO: MAYBE CHANGE NAME TO INITCOMPONENT??
  // TODO: ADD INIT OF TOP LEVEL NODE STATE
  // TODO: BETTER YET MOVE ALL TOP LEVEL STATE FROM NODE TO COMPONENT
  def initChisel () = {
    saveWidthWarnings = false
    saveConnectionWarnings = false
    saveComponentTrace = false
    findCombLoop = false
    isGenHarness = false;
    isDebug = false;
    isFolding = false;
    isReportDims = false;
    scanFormat = "";
    scanArgs = new Array[Node](0);
    printFormat = "";
    printArgs = new ArrayBuffer[Node]();
    isCoercingArgs = true;
    targetEmulatorRootDir = System.getProperty("CHISEL_EMULATOR_ROOT");
    if (targetEmulatorRootDir == null) targetEmulatorRootDir = "../emulator";
    targetVerilogRootDir = System.getProperty("CHISEL_VERILOG_ROOT");
    if (targetVerilogRootDir == null) targetVerilogRootDir = "../verilog";
    targetDir = "";
    configStr = "";
    compIndex = -1;
    compIndices.clear();
    components.clear();
    compStack.clear();
    stackIndent = 0;
    firstComp = true;
    printStackStruct.clear();
    procs.clear();
    muxes.clear();
    ioMap = Map[Node, Int]();
    ioCount = 0;
    isEmittingComponents = false;
    isEmittingC = false;
    topComponent = null;
  }

  def ensure_dir(dir: String) = {
    val d = dir + (if (dir == "" || dir(dir.length-1) == '/') "" else "/");
    new File(d).mkdirs();
    d
  }

  //component stack handling stuff
  
  def isSubclassOfComponent(x: java.lang.Class[ _ ]): Boolean = {
    val classString = x.toString;
    if(classString == "class java.lang.Object")
      return false;
    else if(classString == "class Chisel.Component")
      return true;
    else
      isSubclassOfComponent(x.getSuperclass)
  }

  def printStack = {
    var res = ""
    for((i, c) <- printStackStruct){
      val dispName = if(c.moduleName == "") c.className else c.moduleName
      res += (genIndent(i) + dispName + " " + c.instanceName + "\n")
    }
    println(res)
  }

  def genIndent(x: Int): String = {
    if(x == 0)
      return ""
    else 
      return "    " + genIndent(x-1);
  }

  def nameChildren(root: Component) = {
    val walked = new HashSet[Component] // this is overkill, but just to be safe
    
    //initialize bfs queue of Components
    val bfsQueue = new Queue[Component]()
    bfsQueue.enqueue(root)

    // if it popped off the queue, then it already has an instance name
    while(!bfsQueue.isEmpty) {
      val top = bfsQueue.dequeue
      walked += top
      for(child <- top.children){
        top.nameChild(child)
        if(!walked.contains(child)) bfsQueue.enqueue(child)
      }
    }
  }

  def push(c: Component){
    if(firstComp){
      compStack.push(c);
      firstComp = false;
      printStackStruct += ((stackIndent, c));
    } else {
      val st = Thread.currentThread.getStackTrace;
      //for(elm <- st)
      //println(elm.getClassName + " " + elm.getMethodName + " " + elm.getLineNumber);
      var skip = 3;
      for(elm <- st){
	if(skip > 0) {
	  skip -= 1;
	} else {
	  if(elm.getMethodName == "<init>") {

	    val className = elm.getClassName;

	    if(isSubclassOfComponent(Class.forName(className)) && !c.isSubclassOf(Class.forName(className))) {
              if(saveComponentTrace)
	        println("marking " +className+ " as parent of " + c.getClass);
	      while(compStack.top.getClass != Class.forName(className)){
		pop;
	      }

              val dad = compStack.top;
	      c.parent = dad;
              dad.children += c;

	      compStack.push(c);
	      stackIndent += 1;
	      printStackStruct += ((stackIndent, c));
	      return;
	    }
	  }
	}
      }
    }
  }

  def pop(){
    compStack.pop;
    stackIndent -= 1;
  }

  def getComponent(): Component = if(compStack.length != 0) compStack.top else { 
    // val st = Thread.currentThread.getStackTrace;
    // println("UNKNOWN COMPONENT "); 
    // for(frame <- st)
    //   println("  " + frame);
    null 
  };
  
  def assignResets() {
    for(c <- components) {
      if(c.reset.inputs.length == 0 && c.parent != null)
	c.reset.inputs += c.parent.reset
    }
  }
}

abstract class Component(resetSignal: Bool = null) {
  var ioVal: Data = null;
  var name: String = "";
  val bindings = new ArrayBuffer[Binding];
  var wiresCache: Array[(String, IO)] = null;
  var parent: Component = null;
  var containsReg = false;
  val children = new ArrayBuffer[Component];
  var inputs = new ArrayBuffer[Node];
  var outputs = new ArrayBuffer[Node];
  val asserts = ArrayBuffer[Assert]();
  
  val mods  = new ArrayBuffer[Node];
  val omods = new ArrayBuffer[Node];
  val gmods = new ArrayBuffer[Node];
  val regs  = new ArrayBuffer[Node];
  val nexts = new Queue[Node];
  var nindex = -1;
  var defaultWidth = 32;
  var moduleName: String = "";
  var className:  String = "";
  var instanceName: String = "";
  var pathName: String = "";
  var pathParent: Component = null;
  val childNames = new HashMap[String, Int];
  var named = false;
  var verilog_parameters = "";
  components += this;

  push(this);

  //true if this is a subclass of x
  def isSubclassOf(x: java.lang.Class[ _ ]): Boolean = {
    var className = this.getClass;
    while(className.toString != x.toString){
      if(className.toString == "class Chisel.Component") return false;
      className = className.getSuperclass;
    }
    return true;
  }

  def depthString(depth: Int): String = {
    var res = "";
    for (i <- 0 until depth)
      res += "  ";
    res
  }
  def ownIo() = {
    // println("COMPONENT " + name + " IO " + io);
    val wires = io.flatten;
    for ((n, w) <- wires) {
      // println(">>> " + w + " IN " + this);
      w.component = this;
    }
  }
  def name_it() = {
    val cname  = getClass().getName(); 
    val dotPos = cname.lastIndexOf('.');
    name = if (dotPos >= 0) cname.substring(dotPos+1) else cname;
    className = name;
    if (compIndices contains name) {
      val compIndex = (compIndices(name) + 1);
      compIndices += (name -> compIndex);
      name = name + "_" + compIndex;
    } else {
      compIndices += (name -> 0);
    }
  }
  def findBinding(m: Node): Binding = {
    // println("FINDING BINDING " + m + " OUT OF " + bindings.length + " IN " + this);
    for (b <- bindings) {
      // println("LOOKING AT " + b + " INPUT " + b.inputs(0));
      if (b.inputs(0) == m)
        return b
    }
    // println("UNABLE TO FIND BINDING FOR " + m);
    return null
  }
  //def io: Data = ioVal;
  def io: Data
  def nextIndex : Int = { nindex = nindex + 1; nindex }
  val nameSpace = new HashSet[String];
  def genName (name: String): String = 
    if (name == null || name.length() == 0) "" else this.instanceName + "_" + name;
  var isWalking = new HashSet[Node];
  var isWalked = new HashSet[Node];
  override def toString: String = name
  def wires: Array[(String, IO)] = {
    if (wiresCache == null)
      wiresCache = io.flatten;
    wiresCache
  }
  def assert(cond: Bool, message: String) = 
    asserts += Assert(cond, message);
  def <>(src: Component) = io <> src.io;
  def apply(name: String): Data = io(name);
  // COMPILATION OF REFERENCE
  def emitDec: String = {
    var res = "";
    val wires = io.flatten;
    for ((n, w) <- wires) 
      res += w.emitDec;
    res
  }

  val reset = Bool(INPUT)
  resetList += reset
  reset.component = this
  reset.setName("reset")
  if(!(resetSignal == null)) reset := resetSignal

  def emitRef: String = if (isEmittingC) emitRefC else emitRefV;
  def emitRefC: String = emitRefV;
  def emitRefV: String = name;
  def emitDef: String = {
    val spacing = (if(verilog_parameters != "") " " else "");
    var res = "  " + moduleName + " " +verilog_parameters+ spacing + instanceName + "(";
    val hasReg = containsReg || childrenContainsReg;
    res = res + (if(hasReg) ".clk(clk), .reset(" + (if(reset.inputs.length==0) "reset" else reset.inputs(0).emitRef) + ")" else "");
    var isFirst = true;
    var nl = ""
    for ((n, w) <- wires) {
      if(n != "reset") {
	if (isFirst && !hasReg) {isFirst = false; nl = "\n"} else nl = ",\n";
	res += nl + "       ." + n + "( ";
	//if(w.isInstanceOf[IO]) println("WALKED TO " + w + ": " + w.walked);
	//if(w.isInstanceOf[IO])
	//println("COMP WALKED " + w + " is " + this.isWalked.contains(w));
	w match {
          case io: IO  => 
            if (io.dir == INPUT) {
              if (io.inputs.length == 0) { 
                  if(saveConnectionWarnings)
		    connWriter.write("// " + io + " UNCONNECTED IN " + io.component + "\n"); 
              } else if (io.inputs.length > 1) {
                  if(saveConnectionWarnings)
		    connWriter.write("// " + io + " CONNECTED TOO MUCH " + io.inputs.length + "\n"); 
	      } else if (!this.isWalked.contains(w)){ 
                  if(saveConnectionWarnings)
		    connWriter.write("// UNUSED INPUT " +io+ " OF " + this + " IS REMOVED" + "\n");
              } else {
		res += io.inputs(0).emitRef;
              }
            } else {
              if (io.consumers.length == 0) {
                  if(saveConnectionWarnings)
		    connWriter.write("// " + io + " UNCONNECTED IN " + io.component + " BINDING " + findBinding(io) + "\n"); 
              } else {
		var consumer: Node = parent.findBinding(io);
		if (consumer == null) {
                  if(saveConnectionWarnings)
                    connWriter.write("// " + io + "(" + io.component + ") OUTPUT UNCONNECTED (" + io.consumers.length + ") IN " + parent + "\n"); 
		} else {
                  res += consumer.emitRef; // TODO: FIX THIS?
                }
              }
            }
	};
	res += " )";
      }
    }
    res += ");\n";
    res
  }
  def emitDefLoC: String = {
    var res = "";
    for ((n, w) <- wires) {
      w match {
        case io: IO  => 
          if (io.dir == INPUT)
            res += "  " + emitRef + "->" + n + " = " + io.inputs(0).emitRef + ";\n";
      };
    }
    res += emitRef + "->clock_lo(reset);\n";
    for ((n, w) <- wires) {
      w match {
        case io: IO => 
          if (io.dir == OUTPUT)
            res += "  " + io.consumers(0).emitRef + " = " + emitRef + "->" + n + ";\n";
      };
    }
    res
  }
  def emitDefHiC: String = {
    var res = emitRef + "->clock_hi(reset);\n";
    res
  }
  // COMPILATION OF BODY
  def emitDefs: String = {
    var res = "";
    for (m <- mods)
      res += m.emitDef;
    for (c <- children) 
      res += c.emitDef;
    res
  }
  def emitRegs: String = {
    var res = "  always @(posedge clk) begin\n";
    for (m <- mods) 
      res += m.emitReg;
    res += "  end\n";
    res
  }
  def emitDecs: String = {
    var res = "";
    for (m <- mods) {
      res += m.emitDec;
    }
    res
  }
  def isInferenceTerminal(m: Node): Boolean = {
    m.isFixedWidth || (
      m match { 
        case io: IO => true; 
        case b: Binding => true; 
        case _ => false }
    )
    /*
    var isAllKnown = true;
    for (i <- m.inputs) {
      if (i.width == -1)
        isAllKnown = false;
    }
    isAllKnown
    */
  }

/*
  def initInference() = {
    for (m <- mods) {
      // if (isInferenceTerminal(m)) {
        // println("ENQUEUE " + m);
      // println("INIT " + m);
        nexts.enqueue(m);
      // }
    }
  }
  def inferAll() = {
    initInference;
    var inferMax = 0;
    var maxNode: Node = null;
    while (!nexts.isEmpty) {
      val next = nexts.dequeue();
      if (next.infer) {
        nexts.enqueue(next);
        for (c <- next.consumers) {
          // println("ENQUEUING " + c);
          nexts.enqueue(c);
        }
        for (i <- next.inputs){
          nexts.enqueue(i);
	}
      }
      if(next.inferCount > inferMax) {
	inferMax = next.inferCount;
	maxNode = next;
      }
    }
    //println("MAXIMUM INFER WALK = " + inferMax + " ON " + maxNode + " which is a " + maxNode.getClass);
  }
*/

  def inferAll(): Unit = {
    var nodesList = ArrayBuffer[Node]()
    val walked = new HashSet[Node]
    val bfsQueue = new Queue[Node]

    // initialize bfsQueue
    for((n, elm) <- io.flatten) 
      if(elm.isInstanceOf[IO] && elm.asInstanceOf[IO].dir == OUTPUT)
  	bfsQueue.enqueue(elm)
    for(a <- asserts) 
      bfsQueue.enqueue(a)
    
    for(r <- resetList)
      bfsQueue.enqueue(r)
    // conduct bfs to find all reachable nodes
    while(!bfsQueue.isEmpty){
      val top = bfsQueue.dequeue
      walked += top
      nodesList += top
      for(i <- top.inputs) 
        if(!(i == null)) {
  	  if(!walked.contains(i)) {
  	    bfsQueue.enqueue(i) 
  	  }
        }
    }
    var count = 0

    // bellman-ford to infer all widths
    for(i <- 0 until nodesList.length) {

      var done = true;
      for(elm <- nodesList){
	val updated = elm.infer
  	done = done && !updated
	//done = done && !(elm.infer) TODO: why is this line not the same as previous two?
      }

      count += 1

      if(done){
  	for(elm <- nodesList)
  	  if (elm.width == -1) println("Error");
  	println(count)
  	return;
      }
    }
    println(count)
  }

  def removeCellIOs() {
    val walked = new HashSet[Node]
    val bfsQueue = new Queue[Node]

    def getNode(x: Node): Node = {
      var res = x
      while(res.isCellIO && res.inputs.length != 0){
	res = res.inputs(0)
      }
      res
    }

    // initialize bfsQueue
    for((n, elm) <- io.flatten) {
      elm.removeCellIOs
      if(elm.isInstanceOf[IO] && elm.asInstanceOf[IO].dir == OUTPUT)
  	bfsQueue.enqueue(elm)
    }

    for(r <- resetList)
      bfsQueue.enqueue(r)

    var count = 0

    while(!bfsQueue.isEmpty) {
      val top = bfsQueue.dequeue
      //top.removeCellIOs
      walked += top
      count += 1

      // for(node <- top.inputs) {
      //   if(!(node == null)) {
      //     node.removeCellIOs
      //     if(!walked.contains(node)) bfsQueue.enqueue(node)
      //   }
      // }

      for(i <- 0 until top.inputs.length) {
        if(!(top.inputs(i) == null)) {
          if(top.inputs(i).isCellIO) top.inputs(i) = getNode(top.inputs(i))
          if(!walked.contains(top.inputs(i))) bfsQueue.enqueue(top.inputs(i))
        }
      }

    }
    
    println(count)
  }

  def forceMatchingWidths = {
    for((io, i) <- ioMap) {

      if(!io.isCellIO && io.isInstanceOf[IO] && io.inputs.length == 1) {

	if (io.width > io.inputs(0).width){

          if(saveWidthWarnings) {
	    widthWriter.write("TOO LONG! IO " + io + " with width " + io.width + " bit(s) is assigned a wire with width " + io.inputs(0).width + " bit(s).\n")
          }
	  if(io.inputs(0).isInstanceOf[Fix]){
	    val topBit = NodeExtract(io.inputs(0), Literal(io.inputs(0).width-1)); topBit.infer
	    val fill = NodeFill(io.width - io.inputs(0).width, topBit); fill.infer
	    val res = Concatanate(fill, io.inputs(0)); res.infer
	    io.inputs(0) = res
	  } else {
	    val topBit = Literal(0,1)
	    val fill = NodeFill(io.width - io.inputs(0).width, topBit); fill.infer
	    val res = Concatanate(fill, io.inputs(0)); res.infer
	    io.inputs(0) = res
	  }

	} else if (io.width < io.inputs(0).width) {
          if(saveWidthWarnings) {
	    widthWriter.write("TOO SHORT! IO " + io + " width width " + io.width + " bit(s) is assigned a wire with width " + io.inputs(0).width + " bit(s).\n")
          }
	  val res = NodeExtract(io.inputs(0), io.width-1, 0); res.infer
	  io.inputs(0) = res
	}

      }

    }
    if(saveWidthWarnings) widthWriter.close()
  }

  def findConsumers() = {
    for (m <- mods) {
      m.addConsumers;
    }
  }
  def findRoots(): ArrayBuffer[Node] = {
    val roots = new ArrayBuffer[Node];
    for (a <- asserts) 
      roots += a.cond;
    for (m <- mods) {
      m match {
        case io: IO => if (io.dir == OUTPUT) { if (io.consumers.length == 0) roots += m; }
        case d: Delay  => roots += m;
	case mr: MemRef[ _ ] => if(mr.isReg) roots += m;
        case any       =>
      }
    }
    roots
  }
  def findLeaves(): ArrayBuffer[Node] = {
    val leaves = new ArrayBuffer[Node];
    for (m <- mods) {
      m match {
        case io: IO    => if (io.dir == INPUT && !io.isCellIO) { if (io.inputs.length == 0) leaves += m; }
        case l: Literal    => leaves += m;
        case d: Delay  => leaves += m;
	case mr: MemRef[ _ ] => if(mr.isReg) leaves += m;
        case any       =>
      }
    }
    leaves
  }
  def visitNodes(roots: Array[Node]) = {
    val stack = new Stack[(Int, Node)]();
    for (root <- roots)
      stack.push((0, root));
    isWalked.clear();
    while (stack.length > 0) {
      val (depth, node) = stack.pop();
      node.visitNode(depth, stack);
    }
  }
  def visitNodesRev(roots: Array[Node]) = {
    val stack = new Stack[(Int, Node)]();
    for (root <- roots)
      stack.push((0, root));
    isWalked.clear();
    while (stack.length > 0) {
      val (depth, node) = stack.pop();
      node.visitNodeRev(depth, stack);
    }
  }

  def findOrdering() = visitNodes(findRoots().toArray);
  def findGraph() = visitNodesRev(findLeaves().toArray);

  def findGraphDims(): (Int, Int, Int) = {
    var maxDepth = 0;
    val imods = new ArrayBuffer[Node]();
    for (m <- mods) {
      m match {
        case o: IO  =>
        case l: Literal =>
        case i      => imods += m;
      }
    }
    val whist = new HashMap[Int, Int]();
    for (m <- imods) {
      val w = m.width;
      if (whist.contains(w))
        whist(w) = whist(w) + 1;
      else
        whist(w) = 1;
    }
    val hist = new HashMap[String, Int]();
    for (m <- imods) {
      var name = m.getClass().getName();
      m match {
        case m: Mux => name = "Mux";
        case op: Op => name = op.op;
        case o      => name = name.substring(name.indexOf('.')+1);
      }
      if (hist.contains(name))
        hist(name) = hist(name) + 1;
      else
        hist(name) = 1;
    }
    for (m <- imods) 
      maxDepth = max(m.depth, maxDepth);
    // for ((n, c) <- hist) 
    println("%6s: %s".format("name", "count"));
    for (n <- hist.keys.toList.sortWith((a, b) => a < b)) 
      println("%6s: %4d".format(n, hist(n)));
    println("%6s: %s".format("width", "count"));
    for (w <- whist.keys.toList.sortWith((a, b) => a < b)) 
      println("%3d: %4d".format(w, whist(w)));
    var widths = new Array[Int](maxDepth+1);
    for (i <- 0 until maxDepth+1)
      widths(i) = 0;
    for (m <- imods) 
      widths(m.depth) = widths(m.depth) + 1;
    var numNodes = 0;
    for (m <- imods) 
      numNodes += 1;
    var maxWidth = 0;
    for (i <- 0 until maxDepth+1)
      maxWidth = max(maxWidth, widths(i));
    (numNodes, maxWidth, maxDepth)
  }
  def collectNodes(c: Component) = {
    for (m <- c.mods) {
      // println("M " + m.name);
      m match {
        case io: IO  => 
          if (io.dir == INPUT) 
            inputs += m;
          else
            outputs += m;
        case r: Reg    => regs += m;
        case other     =>
      }
    }
  }
  def traceableNodes = io.traceableNodes;
  def childrenContainsReg: Boolean = {
    var res = containsReg;
    if(children.isEmpty) return res; 
    for(child <- children){
      res = res || child.containsReg || child.childrenContainsReg;
      if(res) return res;
    }
    res
  }
  def doCompileV(out: java.io.FileWriter, depth: Int): Unit = {
    // println("COMPILING COMP " + name);
    println("// " + depthString(depth) + "COMPILING " + this + " " + children.length + " CHILDREN");
    if (isEmittingComponents) {
      for (top <- children)
        top.doCompileV(out, depth+1);
    } else
      topComponent = this;
    // isWalked.clear();
    findConsumers();
    if(!ChiselErrors.isEmpty){
      for(err <- ChiselErrors) err.printError;
      throw new IllegalStateException("CODE HAS " + ChiselErrors.length +" ERRORS");
    }
    //inferAll();
    collectNodes(this);
    // for (m <- mods) {
    //   println("// " + depthString(depth+1) + " MOD " + m);
    // }
    val hasReg = containsReg || childrenContainsReg;
    var res = (if (hasReg) "input clk, input reset" else "");
    var first = true;
    var nl = "";
    for ((n, w) <- wires) {
      if(first && !hasReg) {first = false; nl = "\n"} else nl = ",\n";
      w match {
        case io: IO => {
          if (io.dir == INPUT) {
	    res += nl + "    input " + io.emitWidth + " " + io.emitRef;
          } else {
	    res += nl + "    output" + io.emitWidth + " " + io.emitRef;
          }
        }
      };
    }
    res += ");\n\n";
    // TODO: NOT SURE EXACTLY WHY I NEED TO PRECOMPUTE TMPS HERE
    for (m <- mods)
      m.emitTmp;
    res += emitDecs + "\n" + emitDefs
    // for (o <- outputs)
    //   out.writeln("  assign " + o.emitRef + " = " + o.inputs(0).emitRef + ";");
    if (regs.size > 0) {
      res += "\n" + emitRegs;
    }
    res += "endmodule\n\n";
    if(compDefs contains res){
      moduleName = compDefs(res);
    }else{
      if(compDefs.values.toList contains name) 
	moduleName = genCompName(name);
      else
	moduleName = name;
      compDefs += (res -> moduleName);
      res = "module " + moduleName + "(" + res;
      out.write(res); 
    }
    // println("// " + depthString(depth) + "DONE");
  }
  def markComponent() = {
    name_it();
    ownIo();
    io.name_it("");
    // println("COMPONENT " + name);
    val c = getClass();
    for (m <- c.getDeclaredMethods) {
      val name = m.getName();
      // println("LOOKING FOR " + name);
      val types = m.getParameterTypes();
      if (types.length == 0) {
        val o = m.invoke(this);
        o match { 
	  //case comp: Component => { comp.component = this;}
          case node: Node => { if ((node.isCellIO || (node.name == "" && !node.named) || node.name == null)) node.name_it(name, true);
			       if (node.isReg || node.isRegOut || node.isClkInput) containsReg = true;
			      nameSpace += name;
			    }
	  case buf: ArrayBuffer[Node] => {
	    var i = 0;
	    if(buf(0).isInstanceOf[Node]){
	      for(elm <- buf){
		if ((elm.isCellIO || (elm.name == "" && !elm.named) || elm.name == null)) 
		  elm.name_it(name + "_" + i, true);
		if (elm.isReg || elm.isRegOut || elm.isClkInput) 
		  containsReg = true;
		nameSpace += name + "_" + i;
		i += 1;
	      }
	    }
	  }
          // TODO: THIS CASE MAY NEVER MATCH
	  case bufbuf: ArrayBuffer[ArrayBuffer[ _ ]] => {
	    var i = 0;
	    println(name);
	    for(buf <- bufbuf){
	      var j = 0;
	      for(elm <- buf){
		elm match {
		  case node: Node => {
		    if ((node.isCellIO || (node.name == "" && !node.named) || node.name == null)) 
		      node.name_it(name + "_" + i + "_" + j, true);
		    if (node.isReg || node.isRegOut || node.isClkInput) 
		      containsReg = true;
		    nameSpace += name + "_" + i + "_" + j;
		    j += 1;
		  }
		  case any =>
		}
	      }
	      i += 1;
	    }
	  }
	  case cell: Cell => { cell.name = name;
			       cell.named = true;
			      if(cell.isReg) containsReg = true;
			      nameSpace += name;
			    }
	  case bb: BlackBox => {
            if(!bb.named) {bb.instanceName = name; bb.named = true};
            bb.pathParent = this;
            //bb.name = name;
            //bb.named = true;
            for((n, elm) <- io.flatten) {
              if (elm.isClkInput) containsReg = true
            }
	    nameSpace += name;
          }
	  case comp: Component => {
            if(!comp.named) {comp.instanceName = name; comp.named = true};
            comp.pathParent = this;
	    nameSpace += name;
          }
          case any =>
        }
      }
    }
  }

  def nameChild(child: Component) = {
    if(!child.named){
      if(childNames contains child.className){
	childNames(child.className)+=1;
	child.name = child.className + "_" + childNames(child.className);
      } else {
	childNames += (child.className -> 0);
	child.name = child.className;
      }
      child.instanceName = child.name;
      child.named = true;
    }
  }

  def compileV(): Unit = {
    topComponent = this;
    components.foreach(_.elaborate(0));
    for (c <- components)
      c.markComponent();
    genAllMuxes;
    components.foreach(_.postMarkNet(0));
    assignResets()
    removeCellIOs()
    inferAll();
    val base_name = ensure_dir(targetVerilogRootDir + "/" + targetDir);
    if(saveWidthWarnings)
      widthWriter = new java.io.FileWriter(base_name + name + ".width.warnings")
    forceMatchingWidths;
    nameChildren(topComponent)
    traceNodes();
    val out = new java.io.FileWriter(base_name + name + ".v");
    if(saveConnectionWarnings)
      connWriter = new java.io.FileWriter(base_name + name + ".connection.warnings")
    doCompileV(out, 0);
    verifyAllMuxes;
    if(saveConnectionWarnings)
      connWriter.close()
    if(ChiselErrors isEmpty)
      out.close();
    else {
      for(err <- ChiselErrors)	err.printError;
      throw new IllegalStateException("CODE HAS " + ChiselErrors.length +" ERRORS");
    }
    if (configStr.length > 0) {
      val out_conf = new java.io.FileWriter(base_name+Component.topComponent.name+".conf");
      out_conf.write(configStr);
      out_conf.close();
    }
    if(saveComponentTrace)
      printStack
    compDefs.clear;
    genCount = 0;
  }
  def nameAllIO(): Unit = {
    // println("NAMING " + this);
    io.name_it("");
    for (child <- children) 
      child.nameAllIO();
  }
  def genAllMuxes = {
    for (p <- procs) {
      p match {
        case io: IO  => if(io.updates.length > 0) io.genMuxes(io.default);
        case w: Wire => w.genMuxes(w.default);
        case r: Reg  => r.genMuxes(r);
        case m: Mem[_] => m.genMuxes(m);
        case mr: MemRef[_] =>
        case a: Assign[_] =>
        case e: Extract =>
        case v: VecProc =>
      }
    }
  }
  def verifyAllMuxes = {
    for(m <- muxes) {
      if(m.inputs(0).width != 1 && m.component != null && (!isEmittingComponents || !m.component.isInstanceOf[BlackBox]))
	ChiselErrors += ChiselError("Mux " + m.name + " has " + m.inputs(0).width +"-bit selector " + m.inputs(0).name, m);
    }
  }
  def elaborate(fake: Int = 0) = {}
  def postMarkNet(fake: Int = 0) = {}
  def genHarness(base_name: String, name: String) = {
    val makefile = new java.io.FileWriter(base_name + name + "-makefile");
    makefile.write("CPPFLAGS = -O2 -I../ -I${CHISEL_EMULATOR_INCLUDE}/\n\n");
    makefile.write(name + ": " + name + ".o" + " " + name + "-emulator.o\n");
    makefile.write("\tg++ -o " + name + " " + name + ".o " + name + "-emulator.o\n\n");
    makefile.write(name + ".o: " + name + ".cpp " + name + ".h\n");
    makefile.write("\tg++ -c ${CPPFLAGS} " + name + ".cpp\n\n");
    makefile.write(name + "emulator.o: " + name + "-emulator.cpp " + name + ".h\n");
    makefile.write("\tg++ -c ${CPPFLAGS} " + name + "-emulator.cpp\n\n");
    makefile.close();
    val harness  = new java.io.FileWriter(base_name + name + "-emulator.cpp");
    harness.write("#include \"" + name + ".h\"\n");
    harness.write("int main (int argc, char* argv[]) {\n");
    harness.write("  " + name + "_t* c = new " + name + "_t();\n");
    harness.write("  int lim = (argc > 1) ? atoi(argv[1]) : -1;\n");
    harness.write("  c->init();\n");
    if (isVCD)
      harness.write("  FILE *f = fopen(\"" + name + ".vcd\", \"w\");\n");
    harness.write("  for (int t = 0; lim < 0 || t < lim; t++) {\n");
    harness.write("    dat_t<1> reset = LIT<1>(t == 0);\n");
    harness.write("    if (!c->scan(stdin)) break;\n");
    harness.write("    c->clock_lo(reset);\n");
    harness.write("    c->clock_hi(reset);\n");
    harness.write("    c->print(stdout);\n");
    if (isVCD)
      harness.write("    c->dump(f, t);\n");
    harness.write("  }\n");
    harness.write("}\n");
    harness.close();
  }
  def dumpVCDScope(file: java.io.FileWriter, top: Component, names: HashMap[Node, String]): Unit = {
    file.write("    fprintf(f, \"" + "$scope module " + name + " $end" + "\\n\");\n");
    for (mod <- top.omods) {
      if (mod.component == this && mod.isInVCD) {
        file.write("    fprintf(f, \"$var wire " + mod.width + " " + names(mod) + " " + stripComponent(mod.emitRefVCD) + " $end\\n\");\n");
      
      }
    }
    for (child <- children) {
      child.dumpVCDScope(file, top, names);
    }
    file.write("    fprintf(f, \"$upscope $end\\n\");\n");
  }
  def stripComponent(s: String) = s.split("__").last
  def dumpVCD(file: java.io.FileWriter): Unit = {
    var num = 0;
    val names = new HashMap[Node, String];
    for (mod <- omods) {
      if (mod.isInVCD) {
        names(mod) = "N" + num;
        num += 1;
      }
    }
    file.write("void " + name + "_t::dump(FILE *f, int t) {\n");
    if (isVCD) {
    file.write("  if (t == 0) {\n");
    file.write("    fprintf(f, \"$timescale 1ps $end\\n\");\n");
    dumpVCDScope(file, this, names);
    file.write("    fprintf(f, \"$enddefinitions $end\\n\");\n");
    file.write("    fprintf(f, \"$dumpvars\\n\");\n");
    file.write("    fprintf(f, \"$end\\n\");\n");
    file.write("  }\n");
    file.write("  fprintf(f, \"#%d\\n\", t);\n");
    for (mod <- omods) {
      if (mod.isInVCD && !(mod.name == "reset" && mod.component == this))
        file.write(mod.emitDefVCD(names(mod)));
    }
    }
    file.write("}\n");
  }

  def getPathName: String = {
    val res = (if(instanceName != "") instanceName else name);
    if(pathParent == null)
      return res;
    else
      pathParent.getPathName + "_" + res;
  }

  def traceNodes() = {
    val queue = Stack[() => Any]();
    queue.push(() => io.traceNode(this, queue));
    for (a <- asserts)
      queue.push(() => a.traceNode(this, queue));
    while (queue.length > 0) {
      val work = queue.pop();
      work();
    }
  }

  def compileC(): Unit = {
    components.foreach(_.elaborate(0));
    for (c <- components)
      c.markComponent();
    genAllMuxes;
    components.foreach(_.postMarkNet(0));
    val base_name = ensure_dir(targetEmulatorRootDir + "/" + targetDir);
    val out_h = new java.io.FileWriter(base_name + name + ".h");
    val out_c = new java.io.FileWriter(base_name + name + ".cpp");
    if (isGenHarness)
      genHarness(base_name, name);
    isEmittingC = true;
    println("// COMPILING " + this + "(" + children.length + ")");
    if (isEmittingComponents) {
      io.name_it("");
      for (top <- children)
        top.compileC();
    } else {
      topComponent = this;
    }
    // isWalked.clear();
    assignResets()
    removeCellIOs()
    inferAll();
    if(saveWidthWarnings)
      widthWriter = new java.io.FileWriter(base_name + name + ".width.warnings")
    forceMatchingWidths;
    traceNodes();
    if(!ChiselErrors.isEmpty){
      for(err <- ChiselErrors)	err.printError;
      throw new IllegalStateException("CODE HAS " + ChiselErrors.length + " ERRORS");
      return
    }
    if (!isEmittingComponents) {
      for (c <- components) {
        if (!(c == this)) {
          mods    ++= c.mods;
          asserts ++= c.asserts;
        }
      }
    }
    findConsumers();
    verifyAllMuxes;
    if(!ChiselErrors.isEmpty){
      for(err <- ChiselErrors)	err.printError;
      throw new IllegalStateException("CODE HAS " + ChiselErrors.length + " ERRORS");
      return
    }
    collectNodes(this);
    findOrdering(); // search from roots  -- create omods
    findGraph();    // search from leaves -- create gmods
    for (m <- omods) {
      m match {
        case l: Literal => ;
        case any    => 
          if (m.name != "" && m != reset && !(m.component == null)) {
            //m.name = m.component.name + (if(m.component.instanceName != "") "_" else "") + m.component.instanceName + "__" + m.name;
	    // only modify name if it is not the reset signal of the top component
	    if(m.name != "reset" || !(m.component == this)) 
	      m.name = m.component.getPathName + "__" + m.name;
	  }
      }
      // println(">> " + m.name);
    }
    if (isReportDims) {
    val (numNodes, maxWidth, maxDepth) = findGraphDims();
    println("NUM " + numNodes + " MAX-WIDTH " + maxWidth + " MAX-DEPTH " + maxDepth);
    }
    // for (m <- omods)
    //   println("MOD " + m + " IN " + m.component.name);
    out_h.write("#include \"emulator.h\"\n\n");
    out_h.write("class " + name + "_t : public mod_t {\n");
    out_h.write(" public:\n");
    if (isEmittingComponents) {
      for ((n, w) <- wires) 
        out_h.write("  dat_t<" + w.width + "> " + w.emitRef + ";\n");
    }
    for (m <- omods) {
      if(m.name != "reset" || !(m.component == this)) {
        if (m.isInObject)
          out_h.write(m.emitDecC);
        if (m.isInVCD)
          out_h.write(m.emitDecVCD);
      }
    }
    if (isEmittingComponents) {
      for (c <- children) 
        out_h.write("  " + c.emitRef + "_t* " + c.emitRef + ";\n");
    }
    out_h.write("\n");
    out_h.write("  void init ( bool random_initialization = false );\n");
    out_h.write("  void clock_lo ( dat_t<1> reset );\n");
    out_h.write("  void clock_hi ( dat_t<1> reset );\n");
    out_h.write("  void print ( FILE* f );\n");
    out_h.write("  bool scan ( FILE* f );\n");
    out_h.write("  void dump ( FILE* f, int t );\n");
    out_h.write("};\n");
    out_h.close();

    out_c.write("#include \"" + name + ".h\"\n");
    for(str <- includeArgs) out_c.write("#include \"" + str + "\"\n"); 
    out_c.write("\n");
    out_c.write("void " + name + "_t::init ( bool random_initialization ) {\n");
    if (isEmittingComponents) {
      for (c <- children) {
        out_c.write("  " + c.emitRef + " = new " + c.emitRef + "_t();\n");
        out_c.write("  " + c.emitRef + "->init(random_initialization);\n");
      }
    }
    for (m <- omods) {
      out_c.write(m.emitInitC);
    }
    out_c.write("}\n");
    out_c.write("void " + name + "_t::clock_lo ( dat_t<1> reset ) {\n");
    for (m <- omods) {
      out_c.write(m.emitDefLoC);
    }
    for (a <- asserts) {
      out_c.write("  ASSERT(" + a.cond.emitRefC + ", \"" + a.message + "\");\n");
    }
    // for (c <- children) 
    //   out_c.write("    " + c.emitRef + "->clock_lo(reset);\n");
    out_c.write("}\n");
    out_c.write("void " + name + "_t::clock_hi ( dat_t<1> reset ) {\n");
    for (m <- omods) 
      out_c.write(m.emitDefHiC);
    // for (c <- children) 
    //   out_c.write("    " + c.emitRef + "->clock_hi(reset);\n");
    out_c.write("}\n");
    def splitPrintFormat(s: String) = {
      var off = 0;
      var res: List[String] = Nil;
      for (i <- 0 until s.length) {
        if (s(i) == '%') {
          if (off < i) 
            res = s.substring(off, i) :: res;
          res = "%" :: res;
          if (i == (s.length-1)) {
            println("Badly formed format argument kind: %");
          } else if (s(i+1) != '=') {
            println("Unsupported format argument kind: %" + s(i+1));
          } 
          off = i + 2;
        }
      }
      if (off < (s.length-1))
        res = s.substring(off, s.length) :: res;
      res.reverse
    }
    out_c.write("void " + name + "_t::print ( FILE* f ) {\n");
    if (printArgs.length > 0) {
      val format =
        if (printFormat == "") printArgs.map(a => "%=").reduceLeft((y,z) => z + " " + y) 
        else printFormat;
      val toks = splitPrintFormat(format);
      var i = 0;
      for(i <- 0 until printArgs.length)
	printArgs(i) = printArgs(i).getNode
      for (tok <- toks) {
        if (tok(0) == '%') {
          out_c.write("  fprintf(f, \"%s\", " + printArgs(i).emitRef + ".to_str().c_str());\n");
          i += 1;
        } else {
          out_c.write("  fprintf(f, \"%s\", \"" + tok + "\");\n");
        }
      }
      out_c.write("  fprintf(f, \"\\n\");\n");
    }
    out_c.write("}\n");
    def constantArgSplit(arg: String) = arg.split('=');
    def isConstantArg(arg: String) = constantArgSplit(arg).length == 2;
    out_c.write("bool " + name + "_t::scan ( FILE* f ) {\n");
    if (scanArgs.length > 0) {
      val format = 
        if (scanFormat == "") {
          var res = "";
          for (arg <- scanArgs) {
            if (res.length > 0) res = res + " ";
            res = res + "%llx";
          }
          res
        } else 
          scanFormat;
      out_c.write("  int n = fscanf(f, \"" + format + "\"");
      for (arg <- scanArgs) {
        out_c.write(",  &" + arg.emitRef + ".values[0]");
      }
      out_c.write(");\n");
      out_c.write("  return n == " + scanArgs.length + ";\n");
    }
    out_c.write("}\n");
    dumpVCD(out_c);
    out_c.close();
    if(saveComponentTrace)
      printStack
  }
};

}
