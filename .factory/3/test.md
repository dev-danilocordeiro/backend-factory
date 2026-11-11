# Tests

The tests already in the diff covered every criterion at the routing level. I added `OrderControllerHttpTest`, which starts a real `HttpServer` on an ephemeral port. It checks that `handle()` reads the query string from the request URI, since every existing test passes the query straight to `route(...)`. I only changed test files. The full suite passed, and the implementation behaves as the spec says on every criterion I tested.

## Coverage of acceptance criteria
1. `OrderControllerTest.defaultPageReturnsFirst20Of57`, plus `OrderControllerHttpTest.defaultsOverHttp`, which checks the defaults over real HTTP (with 25 orders rather than 57).
2. `OrderControllerTest.secondPageReturns21To40`; `OrderServiceTest.listPagesInCreationOrder` checks the same slicing in the service.
3. `OrderControllerTest.lastPartialPageReturns17` and `OrderControllerHttpTest.queryParamsAreReadFromRequestUri`.
4. `OrderControllerTest.pagePastEndIsEmpty`; `OrderServiceTest.hugePageDoesNotOverflow` also checks that a very large page number does not overflow.
5. `OrderControllerTest.emptyStoreReturnsEmptyPage`, which compares the whole JSON body.
6. `OrderControllerTest.size100ReturnsAll57`.
7. `OrderControllerTest.sizeOnlyDefaultsPageTo0`.
8. `OrderControllerTest.pageOnlyDefaultsSizeTo20`.
9. `OrderControllerTest.negativePageIs400`, `OrderServiceTest.listRejectsInvalidPageOrSize` and `OrderControllerHttpTest.invalidParamsAre400OverHttp`.
10. `OrderControllerTest.sizeZeroIs400`, `OrderServiceTest.listRejectsInvalidPageOrSize` and `OrderControllerHttpTest.invalidParamsAre400OverHttp`.
11. `OrderControllerTest.size101Is400`, `OrderServiceTest.listRejectsInvalidPageOrSize` and `OrderControllerHttpTest.invalidParamsAre400OverHttp`.
12. `OrderControllerTest.negativeSizeIs400` and `OrderControllerHttpTest.invalidParamsAre400OverHttp`.
13. `OrderControllerTest.nonNumericParamsAre400` (parameterized over all five queries) and `OrderControllerHttpTest.invalidParamsAre400OverHttp`.
14. `OrderControllerTest.getByIdAndPostUnchanged`, plus `OrderControllerHttpTest.getByIdStillReturnsSingleOrder`, which checks over HTTP that the body is still a single JSON object.
15. `OrderControllerTest.unknownParamIgnored` and `OrderControllerHttpTest.queryParamsAreReadFromRequestUri`.
16. `ArchitectureTest`, unchanged. It is set to skip test classes (`DoNotIncludeTests`), so the new test class does not affect the layer rules.

## Result
I ran `mvn -q -f examples/java-service/pom.xml test` and it passed. The only output was SLF4J and `sun.misc.Unsafe` warnings, with no test failures or errors.

Two of my commands were blocked because this session cannot approve permission prompts:
- A file-listing command that used `xargs`. I used the file search and file reader tools instead.
- My first test run, because it printed the exit code with `$?`. I re-ran Maven without that.
