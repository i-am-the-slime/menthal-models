import scala.io.Source
import scala.reflect.io.File
import scala.util.parsing.json.JSON


sourceGenerators in Compile += Def.task {
  type ClassTriplet = (String, String, Seq[(String, String)])
  type AvroFileTriplet = (String, String, Seq[Map[String, Any]])
  def getNameNamespaceAndFields(m:Map[String, Any]): Option[AvroFileTriplet] = {
    for {
      name <- m.get("name")
      deeName = name.asInstanceOf[String]
      namespace = m.getOrElse("namespace", "").asInstanceOf[String]
      f <- m.get("fields")
      fields = f.asInstanceOf[Seq[Map[String, Any]]]
    } yield (deeName, namespace, fields)
  }
  def toCamelToe(s:String):String = s.charAt(0).toUpper + s.tail
  def parseAvroType(tpe:Any):String = {
    tpe match {
      case s:String =>
        toCamelToe(s)
      case complex:Map[String, Any] =>
        complex("type") match {
          case "map" =>
            val values = toCamelToe(complex("values").asInstanceOf[String])
            s"scala.collection.mutable.Map[String, $values]"
          case "array" =>
            val items = toCamelToe(complex("items").asInstanceOf[String])
            s"scala.collection.mutable.Buffer[$items]"
        }
    }
  }
  def jsonMapsToTuples(fields: Seq[Map[String, Any]]):Seq[(String, String)] =
    for{
      field <- fields
      n <- field.get("name")
      name = n.asInstanceOf[String]
      t <- field.get("type")
      tpe = parseAvroType(t)
    } yield (name, tpe)
  def processJson(m:Map[String, Any]): Option[ClassTriplet] = {
    getNameNamespaceAndFields(m) match {
      case Some((name:String, namespace:String, fields:Seq[Map[String, String]])) =>
        val fieldTuples = jsonMapsToTuples(fields)
        Some((name, namespace, fieldTuples))
      case _ => None
    }
  }
  def processFile (file:File): Option[ClassTriplet] = {
    val schema = Source.fromFile(file.path).getLines().reduce(_ + _)
    val json = JSON.parseFull(schema)
    json.flatMap{
      case m:Map[String, Any] =>
        processJson(m)
      case _ =>
        None
    }
  }
  def classTripletsFromAvroDir(avroPath:String):Seq[ClassTriplet] = {
    val directory = File(avroPath).toDirectory
    directory.files.filter(_.extension == "avsc")
      .toList
      .flatMap(processFile)
  }
  def generateImports(ns: String): List[String] = {
    List(
      s"package $ns",
      "import org.apache.avro.specific.SpecificRecord",
      "import scala.collection.JavaConverters._",
      "import java.util",
      "import java.lang",
      "import scala.collection",
      "import Implicits._",
      "\n"
    )
  }
  def generateMainTrait(ns: String): List[String] ={
    List(
      "trait MenthalEvent { ",
      "  def userId:Long",
      "  def time:Long",
      "  def toAvro:SpecificRecord",
      "}\n")
  }
  def generateClasses(classes:Seq[ClassTriplet]):List[String] = {
    classes.flatMap {case (name, _, fieldsList) =>
      val fields = fieldsList.map {case (nm:String,tp:String) => nm+":"+tp}.mkString(", ")
      List(s"case class CC$name($fields) extends MenthalEvent", s"{ def toAvro:$name = this }\n")
    }.toList
  }
  def generateImplicits(classes: Seq[ClassTriplet]): List[String] = {
    val genImplicitsList = classes.flatMap { case (name, _, fields) =>
      val (scalaFields, javaFields) = fields.map { case (nme, tpe) =>
        val camelNme = toCamelToe(nme)
        tpe match {
          case li if li.startsWith("scala.collection.mutable.Buffer") =>
            val regex = "scala.collection.mutable.Buffer\\[(.*)\\]".r
            val elType = regex.findFirstMatchIn(li).get.group(1)
            (s"x.$nme.asJava", s"x.get$camelNme.asScala")
          case m if m.startsWith("scala.collection.mutable.Map") =>
            val regex = "scala.collection.mutable.Map\\[String,(.*)\\]".r
            val elType = regex.findFirstMatchIn(m).get.group(1)
            (s"x.$nme.asJava.asInstanceOf[java.util.Map[String, java.lang.$elType]]",
              s"x.get$camelNme.asScala.asInstanceOf[scala.collection.mutable.Map[String, $elType]]")
          case _ =>
            (s"x.$nme", s"x.get$camelNme")
        }
      }.unzip
      List(s" implicit def toCC$name(x:$name):CC$name = CC$name(${javaFields.mkString(", ")})",
        s" implicit def to$name(x:CC$name):$name = new $name(${scalaFields.mkString(", ")})")
    }.toList
    List("\n","object Implicits{") ::: genImplicitsList ::: List("}\n","\n")
  }
  val avroPath = "./model/avro"
  val groupedClassTriplets = classTripletsFromAvroDir(avroPath)
    .groupBy {case (_,namespace, _) => namespace}
  val paths = groupedClassTriplets.map {
    case (ns, classes) =>
      val genImports = generateImports(ns)
      val genTrait = generateMainTrait(ns)
      val genClasses = generateClasses(classes)
      val genImplicits = generateImplicits(classes)
      val content = genImports ::: genTrait ::: genClasses ::: genImplicits
      val path = (sourceManaged in Compile).value / "compiled_avro" / ns.replace(".","/") / "AvroScalaConversions.scala"
      IO.write(path, content.mkString("\n"))
      path
  }
  paths.toSeq
}.taskValue
