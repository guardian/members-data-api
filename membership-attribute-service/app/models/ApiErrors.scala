package models

object ApiErrors {
  def badRequest(msg: String): ApiError =
    ApiError(
      message = "Bad Request",
      details = msg,
      statusCode = 400
    )

  def notFound: ApiError =
    ApiError(
      message = "Not found",
      details = "Not Found",
      statusCode = 404
    )

  def internalError: ApiError =
    ApiError(
      message = "Internal Server Error",
      details = "Internal Server Error",
      statusCode = 500
    )

  def unauthorized: ApiError =
    ApiError(
      message = "Unauthorised",
      details = "Valid GU_U and SC_GU_U cookies are required.",
      statusCode = 401
    )
}
