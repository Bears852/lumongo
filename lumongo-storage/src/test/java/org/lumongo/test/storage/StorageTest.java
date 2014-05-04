package org.lumongo.test.storage;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.IntField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.NumericRangeQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopScoreDocCollector;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.LockObtainFailedException;
import org.lumongo.LuceneConstants;
import org.lumongo.storage.lucene.DistributedDirectory;
import org.lumongo.storage.lucene.MongoDirectory;
import org.lumongo.util.TestHelper;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeSuite;
import org.testng.annotations.Test;

import com.mongodb.MongoClient;

public class StorageTest {
	private static final String STORAGE_TEST_INDEX = "storageTest";
	private static Directory directory;
	
	@BeforeSuite
	public static void cleanDatabaseAndInit() throws Exception {
		MongoClient mongo = TestHelper.getMongo();
		mongo.dropDatabase(TestHelper.TEST_DATABASE_NAME);
	}
	
	@BeforeClass
	public static void openDirectory() throws Exception {
		System.out.println("Creating lucene directory for storage test");
		MongoClient mongo = TestHelper.getMongo();
		directory = new DistributedDirectory(new MongoDirectory(mongo, TestHelper.TEST_DATABASE_NAME, STORAGE_TEST_INDEX, false, false));
	}
	
	@AfterClass
	public static void closeDirectory() throws Exception {
		System.out.println("Closing lucene directory for storage test");
		directory.close();
	}
	
	@Test(
		groups = "load")
	public void addDocs() throws CorruptIndexException, LockObtainFailedException, IOException {
		StandardAnalyzer analyzer = new StandardAnalyzer(LuceneConstants.VERSION);
		IndexWriterConfig config = new IndexWriterConfig(LuceneConstants.VERSION, analyzer);
		
		IndexWriter w = new IndexWriter(directory, config);
		
		addDoc(w, "Random perl Title that is long", "id-1");
		addDoc(w, "Random java Title that is long", "id-1");
		addDoc(w, "MongoDB is awesome", "id-2");
		addDoc(w, "This is a long title with nothing interesting", "id-3");
		addDoc(w, "Java is awesome", "id-4");
		addDoc(w, "Really big fish", "id-5");
		
		w.commit();
		w.close();
	}
	
	private static void addDoc(IndexWriter w, String title, String uid) throws IOException {
		Document doc = new Document();
		doc.add(new TextField("title", title, Field.Store.YES));
		doc.add(new TextField("uid", uid, Field.Store.YES));
		doc.add(new StringField("uid", uid, Field.Store.YES));
		doc.add(new IntField("testIntField", 3, Field.Store.YES));
		System.out.println(doc.toString());
		Term uidTerm = new Term("uid", uid);
		w.updateDocument(uidTerm, doc);
	}
	
	@Test(
		groups = "query",
		dependsOnGroups = "load")
	public void queryDocs() throws CorruptIndexException, ParseException, IOException {
		IndexReader indexReader = DirectoryReader.open(directory);
		
		StandardAnalyzer analyzer = new StandardAnalyzer(LuceneConstants.VERSION);
		QueryParser qp = new QueryParser(LuceneConstants.VERSION, "title", analyzer) {
			
			@Override
			protected Query getRangeQuery(final String fieldName, final String start, final String end, final boolean startInclusive, final boolean endInclusive)
							throws ParseException {
				
				if ("testIntField".equals(fieldName)) {
					return NumericRangeQuery.newIntRange(fieldName, Integer.parseInt(start), Integer.parseInt(end), startInclusive, endInclusive);
				}
				
				// return default
				return super.getRangeQuery(fieldName, start, end, startInclusive, endInclusive);
				
			}
			
			@Override
			protected Query newTermQuery(org.apache.lucene.index.Term term) {
				String field = term.field();
				String text = term.text();
				if ("testIntField".equals(field)) {
					int value = Integer.parseInt(text);
					return NumericRangeQuery.newIntRange(field, value, value, true, true);
				}
				return super.newTermQuery(term);
			}
		};
		
		int hits = 0;
		
		hits = runQuery(indexReader, qp, "java", 10);
		Assert.assertEquals(hits, 2, "Expected 2 hits");
		hits = runQuery(indexReader, qp, "perl", 10);
		Assert.assertEquals(hits, 0, "Expected 0 hits");
		hits = runQuery(indexReader, qp, "treatment", 10);
		Assert.assertEquals(hits, 0, "Expected 0 hits");
		hits = runQuery(indexReader, qp, "long", 10);
		Assert.assertEquals(hits, 2, "Expected 2 hits");
		hits = runQuery(indexReader, qp, "MongoDB", 10);
		Assert.assertEquals(hits, 1, "Expected 1 hit");
		hits = runQuery(indexReader, qp, "java AND awesome", 10);
		Assert.assertEquals(hits, 1, "Expected 1 hit");
		hits = runQuery(indexReader, qp, "testIntField:[1 TO 10]", 10);
		Assert.assertEquals(hits, 5, "Expected 5 hits");
		hits = runQuery(indexReader, qp, "testIntField:1", 10);
		Assert.assertEquals(hits, 0, "Expected 0 hits");
		hits = runQuery(indexReader, qp, "testIntField:3", 10);
		Assert.assertEquals(hits, 5, "Expected 5 hits");
		
		indexReader.close();
	}
	
	private static int runQuery(IndexReader indexReader, QueryParser qp, String queryStr, int count) throws ParseException, CorruptIndexException, IOException {
		Query q = qp.parse(queryStr);
		
		return runQuery(indexReader, count, q);
		
	}
	
	private static int runQuery(IndexReader indexReader, int count, Query q) throws IOException, CorruptIndexException {
		long start = System.currentTimeMillis();
		IndexSearcher searcher = new IndexSearcher(indexReader);
		TopScoreDocCollector collector = TopScoreDocCollector.create(count, true);
		
		searcher.search(q, collector);
		ScoreDoc[] hits = collector.topDocs().scoreDocs;
		int totalHits = collector.getTotalHits();
		long searchTime = System.currentTimeMillis() - start;
		
		start = System.currentTimeMillis();
		
		List<String> ids = new ArrayList<String>();
		for (int i = 0; i < hits.length; ++i) {
			int docId = hits[i].doc;
			Document d = searcher.doc(docId);
			ids.add(d.get("uid"));
		}
		long fetchTime = System.currentTimeMillis() - start;
		
		System.out.println("Query <" + q.toString() + "> found <" + totalHits + "> total hits in <" + searchTime + "ms>.  Fetched <" + hits.length
						+ "> documents in >" + fetchTime + "ms>");
		
		System.out.println("  :" + ids);
		
		return totalHits;
	}
	
	@Test(
		groups = { "last" },
		dependsOnGroups = { "query" })
	public void apiUsage() throws Exception {
		String hostName = TestHelper.getMongoServer();
		String databaseName = TestHelper.TEST_DATABASE_NAME;
		
		{
			
			MongoClient mongo = new MongoClient(hostName);
			Directory directory = new DistributedDirectory(new MongoDirectory(mongo, databaseName, STORAGE_TEST_INDEX));
			
			StandardAnalyzer analyzer = new StandardAnalyzer(LuceneConstants.VERSION);
			IndexWriterConfig config = new IndexWriterConfig(LuceneConstants.VERSION, analyzer);
			IndexWriter w = new IndexWriter(directory, config);
			
			boolean applyDeletes = true;
			
			IndexReader ir = DirectoryReader.open(w, applyDeletes);
			
			ir.close();
			
			w.commit();
			w.close();
			
			directory.close();
			
		}
		
		{
			
			MongoClient mongo = new MongoClient(hostName);
			Directory d = new DistributedDirectory(new MongoDirectory(mongo, databaseName, STORAGE_TEST_INDEX));
			IndexReader indexReader = DirectoryReader.open(d);
			indexReader.close();
			d.close();
			
		}
		
		{
			MongoClient mongo = new MongoClient(hostName);
			DistributedDirectory d = new DistributedDirectory(new MongoDirectory(mongo, databaseName, STORAGE_TEST_INDEX));
			
			d.copyToFSDirectory(new File("/tmp/fsdirectory"));
			
			d.close();
		}
	}
}