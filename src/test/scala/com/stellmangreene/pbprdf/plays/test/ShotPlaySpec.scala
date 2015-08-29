package com.stellmangreene.pbprdf.plays.test

import org.openrdf.repository.sail.SailRepository
import org.openrdf.sail.memory.MemoryStore
import org.scalatest.FlatSpec
import org.scalatest.Matchers

import com.stellmangreene.pbprdf.plays.ShotPlay
import com.stellmangreene.pbprdf.util.RdfOperations

/**
 * Test the ShotPlay class
 *
 * @author andrewstellman
 */
class ShotPlaySpec extends FlatSpec with Matchers with RdfOperations {

  behavior of "ShotPlay"

  // As long as each event has unique game and event IDs, they can all go into the same repository
  val rep = new SailRepository(new MemoryStore)
  rep.initialize

  it should "parse shots" in {
    new ShotPlay("400610636", 4, 1, "9:18", "Mystics", "Stefanie Dolson misses 13-foot jumper", "0-0").addRdf(rep)

    rep.executeQuery("SELECT * { <http://www.stellman-greene.com/pbprdf/400610636/4> ?p ?o }")
      .map(statement => (s"${statement.getValue("p").stringValue} -> ${statement.getValue("o").stringValue}"))
      .toSet should be(
        Set(
          "http://www.w3.org/1999/02/22-rdf-syntax-ns#type -> http://www.stellman-greene.com/pbprdf#Event",
          "http://www.w3.org/1999/02/22-rdf-syntax-ns#type -> http://www.stellman-greene.com/pbprdf#Play",
          "http://www.w3.org/1999/02/22-rdf-syntax-ns#type -> http://www.stellman-greene.com/pbprdf#Shot",
          "http://www.stellman-greene.com/pbprdf#period -> 1",
          "http://www.stellman-greene.com/pbprdf#time -> 9:18",
          "http://www.stellman-greene.com/pbprdf#secondsIntoGame -> 42",
          "http://www.stellman-greene.com/pbprdf#team -> Mystics",
          "http://www.stellman-greene.com/pbprdf#shotBy -> Stefanie Dolson",
          "http://www.stellman-greene.com/pbprdf#shotType -> 13-foot jumper",
          "http://www.stellman-greene.com/pbprdf#shotMade -> false",
          "http://www.w3.org/2000/01/rdf-schema#label -> Mystics: Stefanie Dolson misses 13-foot jumper"))
  }

  it should "parse assisted shots" in {
    new ShotPlay("400610636", 8, 2, "9:11", "Mystics", "Ivory Latta makes 24-foot three point jumper (Tierra Ruffin-Pratt assists)", "3-0").addRdf(rep)

    rep.executeQuery("SELECT * { <http://www.stellman-greene.com/pbprdf/400610636/8> ?p ?o }")
      .map(statement => (s"${statement.getValue("p").stringValue} -> ${statement.getValue("o").stringValue}"))
      .toSet should be(
        Set(
          "http://www.w3.org/1999/02/22-rdf-syntax-ns#type -> http://www.stellman-greene.com/pbprdf#Event",
          "http://www.w3.org/1999/02/22-rdf-syntax-ns#type -> http://www.stellman-greene.com/pbprdf#Shot",
          "http://www.w3.org/1999/02/22-rdf-syntax-ns#type -> http://www.stellman-greene.com/pbprdf#Play",
          "http://www.stellman-greene.com/pbprdf#period -> 2",
          "http://www.stellman-greene.com/pbprdf#time -> 9:11",
          "http://www.stellman-greene.com/pbprdf#secondsIntoGame -> 649",
          "http://www.stellman-greene.com/pbprdf#team -> Mystics",
          "http://www.stellman-greene.com/pbprdf#shotBy -> Ivory Latta",
          "http://www.stellman-greene.com/pbprdf#shotAssistedBy -> Tierra Ruffin-Pratt",
          "http://www.stellman-greene.com/pbprdf#shotType -> 24-foot three point jumper",
          "http://www.stellman-greene.com/pbprdf#shotMade -> true",
          "http://www.stellman-greene.com/pbprdf#shotPoints -> 3",
          "http://www.w3.org/2000/01/rdf-schema#label -> Mystics: Ivory Latta makes 24-foot three point jumper (Tierra Ruffin-Pratt assists)"))
  }

  it should "parse the correct number of points for free throws" in {
    new ShotPlay("400610636", 88, 2, "9:15", "Sun", "Alyssa Thomas makes free throw 2 of 2", "18-26").addRdf(rep)

    rep.executeQuery("SELECT * { <http://www.stellman-greene.com/pbprdf/400610636/88> ?p ?o }")
      .map(statement => (s"${statement.getValue("p").stringValue} -> ${statement.getValue("o").stringValue}"))
      .toSet should be(
        Set(
          "http://www.w3.org/1999/02/22-rdf-syntax-ns#type -> http://www.stellman-greene.com/pbprdf#Event",
          "http://www.w3.org/1999/02/22-rdf-syntax-ns#type -> http://www.stellman-greene.com/pbprdf#Play",
          "http://www.w3.org/1999/02/22-rdf-syntax-ns#type -> http://www.stellman-greene.com/pbprdf#Shot",
          "http://www.stellman-greene.com/pbprdf#period -> 2",
          "http://www.stellman-greene.com/pbprdf#time -> 9:15",
          "http://www.stellman-greene.com/pbprdf#secondsIntoGame -> 645",
          "http://www.stellman-greene.com/pbprdf#team -> Sun",
          "http://www.stellman-greene.com/pbprdf#shotBy -> Alyssa Thomas",
          "http://www.stellman-greene.com/pbprdf#shotType -> free throw 2 of 2",
          "http://www.stellman-greene.com/pbprdf#shotMade -> true",
          "http://www.stellman-greene.com/pbprdf#shotPoints -> 1",
          "http://www.w3.org/2000/01/rdf-schema#label -> Sun: Alyssa Thomas makes free throw 2 of 2"))
  }

}