package com.ebiznext.comet.schema.generator

import java.io.File

import com.ebiznext.comet.TestHelper
import com.ebiznext.comet.schema.model.{Domain, Format}

class SchemaGenSpec extends TestHelper {
  new WithSettings() {
    "Parsing a sample xlsx file" should "generate a yml file" in {
      SchemaGen.generateSchema(getClass().getResource("/sample/SomeDomainTemplate.xls").getPath)
      val outputFile = new File(settings.comet.metadata + "/someDomain.yml")
      outputFile.exists() shouldBe true
      val result = YamlSerializer.mapper.readValue(outputFile, classOf[Domain])
      result.name shouldBe "someDomain"
      result.schemas.size shouldBe 2
      val schema1 = result.schemas.filter(_.name == "SCHEMA1").head
      schema1.metadata.flatMap(_.format) shouldBe Some(Format.POSITION)
      schema1.attributes.size shouldBe 19
      schema1.merge.flatMap(_.timestamp) shouldBe Some("ATTRIBUTE_1")
      schema1.merge.map(_.key) shouldBe Some(List("ID1", "ID2"))
      val schema2 = result.schemas.filter(_.name == "SCHEMA2").head
      schema2.metadata.flatMap(_.format) shouldBe Some(Format.DSV)
      schema2.attributes.size shouldBe 19
    }

    val reader = new XlsReader(getClass().getResource("/sample/SomeDomainTemplate.xls").getPath)
    val domainOpt = reader.getDomain()

    "a preEncryption domain" should "have only string types" in {
      domainOpt shouldBe defined
      val preEncrypt = SchemaGen.genPreEncryptionDomain(domainOpt.get)
      preEncrypt.schemas.flatMap(_.attributes).filter(_.`type` != "string") shouldBe empty
    }

    "a preEncryption domain" should "have not have required attributes" in {
      domainOpt shouldBe defined
      val preEncrypt = SchemaGen.genPreEncryptionDomain(domainOpt.get)
      preEncrypt.schemas.flatMap(_.attributes).filter(_.required) shouldBe empty
    }

    "a postEncryption domain" should "have not have POSITION schemas" in {
      domainOpt shouldBe defined
      domainOpt.get.schemas
        .flatMap(_.metadata)
        .filter(_.format.equals(Some(Format.POSITION)))
        .size shouldBe 1
      val postEncrypt = SchemaGen.genPostEncryptionDomain(domainOpt.get)
      postEncrypt.schemas
        .flatMap(_.metadata)
        .filter(_.format.equals(Some(Format.POSITION))) shouldBe empty
    }
  }
}
