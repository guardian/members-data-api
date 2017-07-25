package prodtest

import org.specs2.mutable.Specification

class AllocatorTest extends Specification {

  "isInTest" should {
    val allocator = new Allocator()
    "allocate 49168201 to the test" in {
      allocator.isInTest("49168201", 20) === true
    }

    "strip leading zeros" in {
      allocator.isInTest("00168201", 20) === true
    }

    "not allocate 49168200 to the test when percentage is zero" in {
      allocator.isInTest("49168200", 0) === false
    }

    "allocate 4916813 to the test if percentage is over thirteen" in {
      allocator.isInTest("4916813", 13) === true
      allocator.isInTest("4916813", 50) === true
      allocator.isInTest("4916813", 5) === false
    }

    "allocate about 20 percent to the test for 100 ids" in {
      val testPercentage = 20
      val inTest = hundredIds filter { id =>
        allocator.isInTest(id, testPercentage)
      }

      inTest.length.toDouble must beCloseTo(testPercentage, delta = testPercentage * 0.1)
    }

  }

  val hundredIds = List(
    "48886147",
    "49168268",
    "95119292",
    "85472194",
    "33997616",
    "71074107",
    "48450529",
    "37636021",
    "99565530",
    "08858012",
    "56811165",
    "69581787",
    "34775253",
    "44041367",
    "31593425",
    "83489300",
    "02382296",
    "05304872",
    "91305601",
    "35542004",
    "53115527",
    "11408318",
    "44630608",
    "22093418",
    "94349930",
    "48483218",
    "31870339",
    "71914990",
    "26372750",
    "99452426",
    "37146193",
    "04556079",
    "32071663",
    "62522856",
    "55732977",
    "06133868",
    "62762645",
    "05241965",
    "55644883",
    "24490055",
    "49966610",
    "06277593",
    "89724406",
    "04921443",
    "24022584",
    "72173073",
    "20101253",
    "89719241",
    "02560795",
    "10278696",
    "23052819",
    "91467400",
    "93310694",
    "28395659",
    "77176541",
    "88013528",
    "70465574",
    "62932279",
    "38941772",
    "69888220",
    "29799878",
    "71707695",
    "77779395",
    "01170302",
    "95265146",
    "27223410",
    "52579866",
    "23356703",
    "85959530",
    "76913297",
    "22502416",
    "45023071",
    "16385031",
    "17437543",
    "40127194",
    "20230121",
    "72550097",
    "38058032",
    "05691991",
    "96202778",
    "60650311",
    "73033769",
    "94446346",
    "82138376",
    "82687777",
    "84103843",
    "51241983",
    "40592614",
    "34679157",
    "22889947",
    "67901361",
    "14465480",
    "12999085",
    "39663887",
    "00898774",
    "32734916",
    "13003696",
    "93427983",
    "63590660",
    "55138328"
  )
}
