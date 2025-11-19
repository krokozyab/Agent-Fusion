package com.orchestrator.context.neo4j

import org.neo4j.driver.Session

class Neo4jSchema(private val driver: Neo4jDriver) {
    
    fun initialize() {
        driver.session().use { session ->
            createConstraints(session)
            createIndexes(session)
        }
    }
    
    private fun createConstraints(session: Session) {
        // File nodes
        session.run("CREATE CONSTRAINT file_path IF NOT EXISTS FOR (f:File) REQUIRE f.path IS UNIQUE")

        // Code structure nodes
        session.run("CREATE CONSTRAINT class_id IF NOT EXISTS FOR (c:Class) REQUIRE c.id IS UNIQUE")
        session.run("CREATE CONSTRAINT method_id IF NOT EXISTS FOR (m:Method) REQUIRE m.id IS UNIQUE")
        session.run("CREATE CONSTRAINT function_id IF NOT EXISTS FOR (f:Function) REQUIRE f.id IS UNIQUE")

        // Document structure nodes
        session.run("CREATE CONSTRAINT document_path IF NOT EXISTS FOR (d:Document) REQUIRE d.path IS UNIQUE")
        session.run("CREATE CONSTRAINT section_id IF NOT EXISTS FOR (s:Section) REQUIRE s.id IS UNIQUE")
        session.run("CREATE CONSTRAINT paragraph_id IF NOT EXISTS FOR (p:Paragraph) REQUIRE p.id IS UNIQUE")

        // Chunk nodes
        session.run("CREATE CONSTRAINT chunk_id IF NOT EXISTS FOR (c:Chunk) REQUIRE c.id IS UNIQUE")

        // Context search nodes - new for Neo4j context search enhancement
        session.run("CREATE CONSTRAINT document_id IF NOT EXISTS FOR (d:Document) REQUIRE d.id IS UNIQUE")
        session.run("CREATE CONSTRAINT document_node_id IF NOT EXISTS FOR (dn:DocumentNode) REQUIRE dn.id IS UNIQUE")
        session.run("CREATE CONSTRAINT section_node_id IF NOT EXISTS FOR (sn:SectionNode) REQUIRE sn.id IS UNIQUE")
        session.run("CREATE CONSTRAINT code_node_id IF NOT EXISTS FOR (cn:CodeNode) REQUIRE cn.id IS UNIQUE")
        session.run("CREATE CONSTRAINT concept_id IF NOT EXISTS FOR (c:Concept) REQUIRE c.id IS UNIQUE")
        session.run("CREATE CONSTRAINT concept_name IF NOT EXISTS FOR (c:Concept) REQUIRE c.name IS UNIQUE")
    }
    
    private fun createIndexes(session: Session) {
        // File indexes
        session.run("CREATE INDEX file_language IF NOT EXISTS FOR (f:File) ON (f.language)")
        session.run("CREATE INDEX file_type IF NOT EXISTS FOR (f:File) ON (f.fileType)")

        // Code structure indexes
        session.run("CREATE INDEX class_name IF NOT EXISTS FOR (c:Class) ON (c.name)")
        session.run("CREATE INDEX method_name IF NOT EXISTS FOR (m:Method) ON (m.name)")
        session.run("CREATE INDEX function_name IF NOT EXISTS FOR (f:Function) ON (f.name)")

        // Document structure indexes
        session.run("CREATE INDEX document_type IF NOT EXISTS FOR (d:Document) ON (d.documentType)")
        session.run("CREATE INDEX section_level IF NOT EXISTS FOR (s:Section) ON (s.level)")

        // Chunk indexes
        session.run("CREATE INDEX chunk_kind IF NOT EXISTS FOR (c:Chunk) ON (c.kind)")

        // Context search indexes - new for Neo4j context search enhancement
        session.run("CREATE INDEX document_node_title IF NOT EXISTS FOR (d:DocumentNode) ON (d.title)")
        session.run("CREATE INDEX document_node_filepath IF NOT EXISTS FOR (d:DocumentNode) ON (d.filepath)")
        session.run("CREATE INDEX section_node_title IF NOT EXISTS FOR (s:SectionNode) ON (s.title)")
        session.run("CREATE INDEX section_node_level IF NOT EXISTS FOR (s:SectionNode) ON (s.level)")
        session.run("CREATE INDEX section_node_filepath IF NOT EXISTS FOR (s:SectionNode) ON (s.filepath)")
        session.run("CREATE INDEX code_node_language IF NOT EXISTS FOR (c:CodeNode) ON (c.language)")
        session.run("CREATE INDEX concept_name_idx IF NOT EXISTS FOR (c:Concept) ON (c.name)")
        session.run("CREATE INDEX concept_category_idx IF NOT EXISTS FOR (c:Concept) ON (c.category)")
    }
}
